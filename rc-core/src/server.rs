//! Loopback HTTP surface for the sidecar.
//!
//! Only a handful of read-only routes are exposed; the process binds 127.0.0.1
//! so it is never reachable from the LAN. An optional shared secret is checked
//! against the `x-rc-secret` header for the future plugin link.
//!
//!   GET /health                      -> status + coverage
//!   GET /stats                       -> index counters
//!   GET /search?q=&limit=&sessionId= -> same item shape as the JS engine
//!
//! Refresh never blocks queries: the heavy half runs without the write lock and
//! the lock is only taken to swap finished rows in.

use std::path::PathBuf;
use std::sync::{Arc, RwLock};
use std::thread;
use std::time::Duration;

use serde_json::{json, Value};
use tiny_http::{Header, Response, Server};

use crate::index::Index;

fn json_response(v: &Value, status: u16) -> Response<std::io::Cursor<Vec<u8>>> {
    let header = Header::from_bytes(&b"Content-Type"[..], &b"application/json; charset=utf-8"[..])
        .expect("static header");
    Response::from_string(v.to_string())
        .with_status_code(status)
        .with_header(header)
}

/// Decode `%XX` and `+` in a query component (no external crate needed).
fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0usize;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
                match u8::from_str_radix(hex, 16) {
                    Ok(b) => {
                        out.push(b);
                        i += 3;
                    }
                    Err(_) => {
                        out.push(bytes[i]);
                        i += 1;
                    }
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).to_string()
}

/// `?a=1&b=2` -> ("a" -> "1")
fn query_params(query: &str) -> Vec<(String, String)> {
    query
        .split('&')
        .filter(|p| !p.is_empty())
        .map(|pair| {
            let mut it = pair.splitn(2, '=');
            let k = percent_decode(it.next().unwrap_or(""));
            let v = percent_decode(it.next().unwrap_or(""));
            (k, v)
        })
        .collect()
}

/// Start the HTTP server. Returns only on a fatal bind error.
pub fn serve(
    port: u16,
    state_dir: PathBuf,
    sessions_root: PathBuf,
    interval_secs: u64,
    secret: Option<String>,
) -> Result<(), String> {
    let mut index = Index::new(&state_dir);
    let loaded = index.load_partitions();
    let shared = Arc::new(RwLock::new(index));

    let server = Server::http(("127.0.0.1", port)).map_err(|e| format!("bind 127.0.0.1:{port} failed: {e}"))?;
    println!(
        "[rc-core] listening on http://127.0.0.1:{port} loaded_partitions={loaded} \
         sessions_root={} state_dir={} interval={}s",
        sessions_root.display(),
        state_dir.display(),
        interval_secs.max(5)
    );

    // Background refresher: heavy walk without the lock, brief lock to apply.
    {
        let shared = Arc::clone(&shared);
        let root = sessions_root.clone();
        thread::spawn(move || loop {
            let outcome = {
                let guard = shared.read().unwrap_or_else(|e| e.into_inner());
                guard.compute_refresh(&root)
            };
            let (ms, scanned, rebuilds) = (outcome.elapsed_ms, outcome.scanned, outcome.rebuilds);
            {
                let mut guard = shared.write().unwrap_or_else(|e| e.into_inner());
                guard.apply_refresh(outcome);
            }
            println!(
                "[rc-core] index pass: scanned={scanned} rebuilt={rebuilds} took={ms}ms"
            );
            thread::sleep(Duration::from_secs(interval_secs.max(5)));
        });
    }

    loop {
        match server.recv_timeout(Duration::from_millis(500)) {
            Ok(Some(request)) => {
                let shared = Arc::clone(&shared);
                let secret = secret.clone();
                thread::spawn(move || {
                    if let Some(expected) = secret {
                        let got = request
                            .headers()
                            .iter()
                            .find(|h| h.field.as_str().as_str().eq_ignore_ascii_case("x-rc-secret"))
                            .map(|h| h.value.as_str().to_string())
                            .unwrap_or_default();
                        if got != expected {
                            let _ = request.respond(json_response(&json!({"error": "unauthorized"}), 401));
                            return;
                        }
                    }
                    let url = request.url().to_string();
                    let (path, query) = match url.split_once('?') {
                        Some((p, q)) => (p.to_string(), q.to_string()),
                        None => (url, String::new()),
                    };
                    let params = query_params(&query);
                    let get = |name: &str| -> Option<String> {
                        params.iter().find(|(k, _)| k == name).map(|(_, v)| v.clone())
                    };
                    let response = match path.as_str() {
                        "/health" => {
                            let g = shared.read().unwrap_or_else(|e| e.into_inner());
                            json_response(
                                &json!({
                                    "status": "ok",
                                    "complete": g.is_complete(),
                                    "sessions": g.session_count(),
                                    "rows": g.row_count(),
                                    "lastIndexMs": g.last_index_ms,
                                    "lastIndexAt": g.last_index_at,
                                }),
                                200,
                            )
                        }
                        "/stats" => {
                            let g = shared.read().unwrap_or_else(|e| e.into_inner());
                            json_response(&g.stats_json(), 200)
                        }
                        "/search" => {
                            let q = get("q").unwrap_or_default();
                            let limit = get("limit")
                                .and_then(|v| v.parse::<usize>().ok())
                                .unwrap_or(20)
                                .clamp(1, 200);
                            let session_id = get("sessionId").filter(|s| !s.is_empty());
                            let t0 = std::time::Instant::now();
                            let out = {
                                let g = shared.read().unwrap_or_else(|e| e.into_inner());
                                g.query(&q, limit, session_id.as_deref())
                            };
                            let mut out = out;
                            out["tookMs"] = json!(t0.elapsed().as_millis());
                            json_response(&out, 200)
                        }
                        _ => json_response(&json!({"error": "not_found", "path": path}), 404),
                    };
                    let _ = request.respond(response);
                });
            }
            Ok(None) => continue,
            Err(e) => return Err(format!("server error: {e}")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn percent_decode_handles_escapes_and_plus() {
        assert_eq!(percent_decode("a%20b+c"), "a b c");
        assert_eq!(percent_decode("%E4%B8%AD%E6%96%87"), "中文");
        assert_eq!(percent_decode("100%"), "100%");
    }

    #[test]
    fn query_params_splits_and_decodes() {
        let p = query_params("q=hello+world&limit=20&sessionId=");
        assert_eq!(p[0], ("q".to_string(), "hello world".to_string()));
        assert_eq!(p[1], ("limit".to_string(), "20".to_string()));
        assert_eq!(p[2], ("sessionId".to_string(), String::new()));
    }
}
