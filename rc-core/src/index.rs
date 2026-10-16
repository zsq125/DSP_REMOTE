//! Session-log walker, zstd frame decoding, partition store and query engine.
//!
//! Layout parity with the JavaScript implementation is deliberate so the sidecar
//! can replace it without changing behaviour:
//!
//!   source of truth : <sessions-root>/<...>/<sessionId>/session.v4.jsonl.zstd
//!   partition       : <state-dir>/search-index/<safe-sid>.jsonl
//!                     line 1 = {"sid","mtimeMs","size","count"}, then rows
//!   meta            : <state-dir>/search-meta.json
//!
//! The durable log is a concatenation of independent zstd frames; a torn tail
//! frame is normal for a file that is still being written, so decode failures
//! skip that frame instead of failing the pass.

use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use serde_json::{json, Value};

use crate::extract::{extract_rows, Row};

pub const ROW_TEXT_CAP: usize = 1500;
pub const MAX_ROWS_PER_SESSION: usize = 20_000;
const ZSTD_MAGIC: [u8; 4] = [0x28, 0xb5, 0x2f, 0xfd];
pub const SESSION_FILE_NAME: &str = "session.v4.jsonl.zstd";

/// One session's indexed rows plus the durable-file identity they came from.
#[derive(Debug, Clone)]
pub struct SessionRows {
    pub sid: String,
    pub mtime_ms: u64,
    pub size: u64,
    pub rows: Vec<Row>,
}

/// Result of the heavy (lock-free) half of a refresh.
pub struct RefreshOutcome {
    /// Sessions whose rows were rebuilt in this pass.
    pub changed: Vec<SessionRows>,
    /// Sessions whose durable log disappeared.
    pub vanished: Vec<String>,
    /// Durable files scanned.
    pub scanned: usize,
    /// Partitions actually rebuilt.
    pub rebuilds: u64,
    /// Wall time of the heavy half.
    pub elapsed_ms: u128,
}

/// Whole index. Kept behind a lock by the server; queries only read.
pub struct Index {
    dir: PathBuf,
    meta_path: PathBuf,
    sessions: HashMap<String, SessionRows>,
    complete: bool,
    pub last_index_ms: u128,
    pub last_index_at: i64,
    pub rebuilds: u64,
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Session ids are [A-Za-z0-9-]; anything else is replaced defensively.
pub fn safe_name(sid: &str) -> String {
    sid.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '_' || c == '-' { c } else { '_' })
        .collect()
}

fn file_identity(p: &Path) -> Option<(u64, u64)> {
    let md = fs::metadata(p).ok()?;
    let mtime = md
        .modified()
        .ok()?
        .duration_since(UNIX_EPOCH)
        .ok()?
        .as_millis() as u64;
    Some((mtime, md.len()))
}

/// Recursively collect `session.v4.jsonl.zstd` paths (iterative: no deep recursion).
pub fn walk_session_files(root: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = fs::read_dir(&dir) else { continue };
        for entry in entries.flatten() {
            let path = entry.path();
            let Ok(ft) = entry.file_type() else { continue };
            if ft.is_dir() {
                stack.push(path);
            } else if path.file_name().and_then(|n| n.to_str()) == Some(SESSION_FILE_NAME) {
                out.push(path);
            }
        }
    }
    out
}

/// Decode one zstd frame. Pure-Rust decoder, no C dependency.
fn decode_frame(bytes: &[u8]) -> Option<Vec<u8>> {
    let mut dec = ruzstd::streaming_decoder::StreamingDecoder::new(bytes).ok()?;
    let mut out = Vec::new();
    dec.read_to_end(&mut out).ok()?;
    Some(out)
}

/// Split a concatenated-frame file at every zstd magic and decode each frame.
fn decode_frames(buf: &[u8]) -> String {
    let mut starts: Vec<usize> = Vec::new();
    let mut pos = 0usize;
    while pos + 4 <= buf.len() {
        match buf[pos..].windows(4).position(|w| w == ZSTD_MAGIC) {
            Some(rel) => {
                let at = pos + rel;
                starts.push(at);
                pos = at + 4;
            }
            None => break,
        }
    }
    if starts.is_empty() {
        return String::new();
    }
    starts.push(buf.len());
    let mut text = String::new();
    for w in starts.windows(2) {
        if let Some(bytes) = decode_frame(&buf[w[0]..w[1]]) {
            text.push_str(&String::from_utf8_lossy(&bytes));
        }
    }
    text
}

/// Parse decoded JSONL text into index rows (cap enforced across frames by caller).
fn rows_from_text(text: &str) -> Vec<Row> {
    let mut events: Vec<Value> = Vec::new();
    for line in text.split('\n') {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if let Ok(v) = serde_json::from_str::<Value>(line) {
            events.push(v);
        }
    }
    extract_rows(&events, MAX_ROWS_PER_SESSION, ROW_TEXT_CAP)
}

fn write_atomic(path: &Path, body: &str) -> std::io::Result<()> {
    let tmp = path.with_extension(format!("tmp-{}", std::process::id()));
    fs::write(&tmp, body)?;
    fs::rename(&tmp, path)
}

impl Index {
    pub fn new(state_dir: &Path) -> Self {
        let dir = state_dir.join("search-index");
        Self {
            meta_path: state_dir.join("search-meta.json"),
            dir,
            sessions: HashMap::new(),
            complete: false,
            last_index_ms: 0,
            last_index_at: 0,
            rebuilds: 0,
        }
    }

    pub fn row_count(&self) -> usize {
        self.sessions.values().map(|s| s.rows.len()).sum()
    }

    pub fn session_count(&self) -> usize {
        self.sessions.len()
    }

    pub fn is_complete(&self) -> bool {
        self.complete
    }

    /// Root of the DSH session store: `%USERPROFILE%\.dsh\sessions`.
    pub fn default_sessions_root() -> PathBuf {
        let home = std::env::var("USERPROFILE")
            .or_else(|_| std::env::var("HOME"))
            .unwrap_or_else(|_| ".".to_string());
        PathBuf::from(home).join(".dsh").join("sessions")
    }

    /// Fast start: load existing partitions instead of re-decoding every log.
    pub fn load_partitions(&mut self) -> usize {
        let mut loaded = 0usize;
        let Ok(entries) = fs::read_dir(&self.dir) else { return 0 };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("jsonl") {
                continue;
            }
            let Ok(raw) = fs::read_to_string(&path) else { continue };
            let mut lines = raw.split('\n');
            let Some(meta_line) = lines.next() else { continue };
            let Ok(meta) = serde_json::from_str::<Value>(meta_line) else { continue };
            let sid = meta.get("sid").and_then(Value::as_str).unwrap_or("").to_string();
            if sid.is_empty() {
                continue;
            }
            let mut rows = Vec::new();
            for line in lines {
                if line.trim().is_empty() {
                    continue;
                }
                let Ok(v) = serde_json::from_str::<Value>(line) else { continue };
                let arr = match v.as_array() {
                    Some(a) if a.len() >= 4 => a,
                    _ => continue,
                };
                rows.push(Row {
                    seq: arr[0].as_i64().unwrap_or(0),
                    role: arr[1].as_str().unwrap_or("").to_string(),
                    ts: arr[2].as_i64().unwrap_or(0),
                    text: arr[3].as_str().unwrap_or("").to_string(),
                });
            }
            self.sessions.insert(
                sid.clone(),
                SessionRows {
                    sid,
                    mtime_ms: meta.get("mtimeMs").and_then(Value::as_u64).unwrap_or(0),
                    size: meta.get("size").and_then(Value::as_u64).unwrap_or(0),
                    rows,
                },
            );
            loaded += 1;
        }
        if loaded > 0 {
            self.complete = true;
        }
        loaded
    }

    /// One refresh pass: rebuild changed/new sessions, drop vanished ones.
    ///
    /// Convenience wrapper around [`Index::compute_refresh`] +
    /// [`Index::apply_refresh`] for CLI use (single-threaded, no lock to hold).
    pub fn refresh(&mut self, sessions_root: &Path) -> u128 {
        let outcome = self.compute_refresh(sessions_root);
        let elapsed = outcome.elapsed_ms;
        self.apply_refresh(outcome);
        elapsed
    }

    /// Heavy half of a refresh: walk + decode + extract + write partitions.
    ///
    /// Takes `&self` on purpose: the server runs this WITHOUT the write lock so
    /// queries keep being served while a full rebuild is in flight.
    pub fn compute_refresh(&self, sessions_root: &Path) -> RefreshOutcome {
        let t0 = Instant::now();
        let entries = walk_session_files(sessions_root);
        let scanned = entries.len();
        let mut changed: Vec<SessionRows> = Vec::new();
        let mut seen: Vec<String> = Vec::with_capacity(entries.len());
        let mut rebuilds = 0u64;

        for abs in entries {
            let sid = abs
                .parent()
                .and_then(|p| p.file_name())
                .and_then(|n| n.to_str())
                .unwrap_or("")
                .to_string();
            if sid.is_empty() {
                continue;
            }
            seen.push(sid.clone());
            let Some((mtime_ms, size)) = file_identity(&abs) else { continue };
            if let Some(known) = self.sessions.get(&sid) {
                if known.mtime_ms == mtime_ms && known.size == size {
                    continue; // unchanged: keep the in-memory rows untouched
                }
            }
            let Ok(buf) = fs::read(&abs) else { continue };
            let rows = rows_from_text(&decode_frames(&buf));
            rebuilds += 1;
            let count = rows.len();
            let body = partition_body(&sid, mtime_ms, size, count, &rows);
            let _ = fs::create_dir_all(&self.dir);
            let _ = write_atomic(&self.dir.join(format!("{}.jsonl", safe_name(&sid))), &body);
            changed.push(SessionRows {
                sid,
                mtime_ms,
                size,
                rows,
            });
            // Give the OS a scheduling point between sessions: keeps disk/CPU
            // bursts short so co-resident processes never see a long stall.
            std::thread::yield_now();
        }

        let keep: std::collections::HashSet<&String> = seen.iter().collect();
        let vanished: Vec<String> = self
            .sessions
            .keys()
            .filter(|k| !keep.contains(*k))
            .cloned()
            .collect();

        RefreshOutcome {
            changed,
            vanished,
            scanned,
            rebuilds,
            elapsed_ms: t0.elapsed().as_millis(),
        }
    }

    /// Cheap half: swap the freshly computed rows in and persist the meta file.
    pub fn apply_refresh(&mut self, outcome: RefreshOutcome) {
        for s in outcome.changed {
            self.sessions.insert(s.sid.clone(), s);
        }
        for sid in outcome.vanished {
            self.sessions.remove(&sid);
            let _ = fs::remove_file(self.dir.join(format!("{}.jsonl", safe_name(&sid))));
        }
        self.complete = true;
        self.rebuilds += outcome.rebuilds;
        self.last_index_ms = outcome.elapsed_ms;
        self.last_index_at = now_ms();
        let files: serde_json::Map<String, Value> = self
            .sessions
            .iter()
            .map(|(sid, s)| {
                (
                    sid.clone(),
                    json!({"sid": s.sid, "mtimeMs": s.mtime_ms, "size": s.size, "count": s.rows.len()}),
                )
            })
            .collect();
        let _ = fs::create_dir_all(self.meta_path.parent().unwrap_or(Path::new(".")));
        let _ = write_atomic(
            &self.meta_path,
            &json!({"version": 1, "complete": true, "files": files}).to_string(),
        );
    }

    pub fn stats_json(&self) -> Value {
        json!({
            "sessions": self.session_count(),
            "rows": self.row_count(),
            "complete": self.complete,
            "rebuilds": self.rebuilds,
            "lastIndexMs": self.last_index_ms,
            "lastIndexAt": self.last_index_at,
        })
    }

    /// Literal AND-of-tokens, case-insensitive substring search over row text.
    ///
    /// Returns the same item shape the JavaScript engine produced, minus session
    /// metadata (title/cwd/updatedAt) which only the in-process plugin can get.
    pub fn query(&self, q: &str, limit: usize, session_id: Option<&str>) -> Value {
        let tokens: Vec<String> = q
            .to_lowercase()
            .split_whitespace()
            .map(|t| t.to_string())
            .collect();
        if tokens.is_empty() {
            return json!({
                "v": 1, "items": [], "complete": self.complete,
                "sessions": self.session_count(), "rows": self.row_count(),
            });
        }
        let mut items: Vec<Value> = Vec::new();
        for (sid, s) in &self.sessions {
            if let Some(want) = session_id {
                if want != sid.as_str() {
                    continue;
                }
            }
            let mut hit_count = 0usize;
            let mut hits: Vec<Value> = Vec::new();
            for row in s.rows.iter().rev() {
                let lower = row.text.to_lowercase();
                let mut ok = true;
                let mut first: Option<usize> = None;
                for tok in &tokens {
                    match lower.find(tok.as_str()) {
                        Some(at) => {
                            if first.is_none() {
                                first = Some(at);
                            }
                        }
                        None => {
                            ok = false;
                            break;
                        }
                    }
                }
                if !ok {
                    continue;
                }
                hit_count += 1;
                if hits.len() < 3 {
                    hits.push(json!({
                        "seq": row.seq,
                        "role": row.role,
                        "ts": row.ts,
                        "snippet": snippet(&row.text, first.unwrap_or(0)),
                    }));
                }
            }
            if hit_count > 0 {
                items.push(json!({
                    "sessionId": sid,
                    "hitCount": hit_count,
                    "hits": hits,
                }));
            }
        }
        // Newest first hit wins; then the session with more hits.
        items.sort_by(|a, b| {
            let ta = a["hits"][0]["ts"].as_i64().unwrap_or(0);
            let tb = b["hits"][0]["ts"].as_i64().unwrap_or(0);
            tb.cmp(&ta)
                .then(b["hitCount"].as_u64().unwrap_or(0).cmp(&a["hitCount"].as_u64().unwrap_or(0)))
        });
        let take = if session_id.is_some() { limit.max(50) } else { limit };
        items.truncate(take);
        json!({
            "v": 1, "items": items, "complete": self.complete,
            "sessions": self.session_count(), "rows": self.row_count(),
        })
    }
}

fn partition_body(sid: &str, mtime_ms: u64, size: u64, count: usize, rows: &[Row]) -> String {
    let mut body = json!({"sid": sid, "mtimeMs": mtime_ms, "size": size, "count": count}).to_string();
    for r in rows {
        body.push('\n');
        body.push_str(&json!([r.seq, r.role, r.ts, r.text]).to_string());
    }
    body
}

/// Snippet around the first token occurrence: 40 chars before, 90 after, with
/// ellipses exactly where the JavaScript version put them.
fn snippet(text: &str, byte_at: usize) -> String {
    let chars: Vec<char> = text.chars().collect();
    let at = text
        .get(..byte_at)
        .map(|p| p.chars().count())
        .unwrap_or(0)
        .min(chars.len());
    let start = at.saturating_sub(40);
    let end = (at + 90).min(chars.len());
    let mut out = String::new();
    if start > 0 {
        out.push('…');
    }
    out.extend(chars[start..end].iter());
    if end < chars.len() {
        out.push('…');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_name_strips_path_hostile_chars() {
        assert_eq!(safe_name("a/b\\c:d"), "a_b_c_d");
    }

    #[test]
    fn snippet_adds_ellipses_like_the_js_version() {
        let long = "x".repeat(200);
        let s = snippet(&long, 100);
        assert!(s.starts_with('…') && s.ends_with('…'));
        // 40 before + 90 after = 130 visible chars between the ellipses.
        assert_eq!(s.chars().count(), 132);

        let short = "hello world";
        assert_eq!(snippet(short, 6), "hello world");
    }

    #[test]
    fn empty_query_returns_no_items() {
        let idx = Index::new(Path::new("."));
        let out = idx.query("   ", 20, None);
        assert_eq!(out["items"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn decode_frames_survives_garbage_input() {
        assert_eq!(decode_frames(b"not a zstd frame"), "");
    }
}
