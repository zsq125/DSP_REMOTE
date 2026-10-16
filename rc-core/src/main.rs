//! rc-core: out-of-process heavy work for the DSP_REMOTE gateway.
//!
//! Rationale: the JavaScript gateway runs as a Cordis plugin INSIDE the DSH
//! process, so its indexing/search work shares DSH's single event loop and can
//! stall it (measured: one broad search blocked the loop for 113 ms; a cold
//! index build is far worse). This binary owns that heavy work in its own
//! process, so DSH's loop is never blocked by it.
//!
//! Commands (all read-only with respect to DSH; the sidecar only reads session
//! logs and writes its own state directory):
//!
//!   rc-core index  [--sessions-root DIR] [--state-dir DIR] [--no-load]
//!   rc-core serve  --port N [--sessions-root DIR] [--state-dir DIR]
//!                  [--interval-secs N] [--secret S] [--no-load]
//!   rc-core query  <q> [--limit N] [--sessionId ID] [--no-load]
//!   rc-core bench  [--passes N] [--sessions-root DIR] [--state-dir DIR]

mod extract;
mod index;
mod priority;
mod server;

use std::collections::HashMap;
use std::path::PathBuf;
use std::process::ExitCode;

use serde_json::json;

use index::Index;

struct Args {
    command: String,
    positional: Vec<String>,
    flags: HashMap<String, String>,
    switches: Vec<String>,
}

impl Args {
    fn parse() -> Args {
        let argv: Vec<String> = std::env::args().skip(1).collect();
        let command = argv
            .first()
            .cloned()
            .unwrap_or_else(|| "help".to_string());
        let mut positional = Vec::new();
        let mut flags = HashMap::new();
        let mut switches = Vec::new();
        let mut i = 1usize;
        while i < argv.len() {
            let arg = argv[i].clone();
            match arg.strip_prefix("--") {
                // --name=value
                Some(name) if name.contains('=') => {
                    let (k, v) = name.split_once('=').unwrap_or((name, ""));
                    flags.insert(k.to_string(), v.to_string());
                    i += 1;
                }
                // --name value  (a following token that is not itself a flag)
                Some(name) => {
                    let next_is_value = argv
                        .get(i + 1)
                        .map(|n| !n.starts_with("--"))
                        .unwrap_or(false);
                    if next_is_value {
                        flags.insert(name.to_string(), argv[i + 1].clone());
                        i += 2;
                    } else {
                        switches.push(name.to_string());
                        i += 1;
                    }
                }
                None => {
                    positional.push(arg);
                    i += 1;
                }
            }
        }
        Args {
            command,
            positional,
            flags,
            switches,
        }
    }

    fn flag(&self, name: &str) -> Option<&str> {
        self.flags.get(name).map(|s| s.as_str())
    }

    fn switch(&self, name: &str) -> bool {
        self.switches.iter().any(|s| s == name)
    }

    fn path(&self, name: &str) -> Option<PathBuf> {
        self.flag(name).map(PathBuf::from)
    }
}

fn state_dir(args: &Args) -> PathBuf {
    args.path("state-dir")
        .unwrap_or_else(|| PathBuf::from(".rc-core-state"))
}

fn sessions_root(args: &Args) -> PathBuf {
    args.path("sessions-root")
        .unwrap_or_else(Index::default_sessions_root)
}

fn build_index(args: &Args) -> Index {
    let mut index = Index::new(&state_dir(args));
    if !args.switch("no-load") {
        let loaded = index.load_partitions();
        eprintln!("[rc-core] loaded partitions: {loaded}");
    }
    index
}

fn main() -> ExitCode {
    let args = Args::parse();
    // Always yield to the process we serve, even when run from a shell by hand.
    if !args.switch("no-throttle") {
        let level = priority::lower_self(args.flag("priority").unwrap_or("below-normal"));
        eprintln!("[rc-core] process priority lowered to: {level}");
    }
    match args.command.as_str() {
        "index" => {
            let root = sessions_root(&args);
            let mut index = build_index(&args);
            let before = (index.session_count(), index.row_count());
            let ms = index.refresh(&root);
            let out = json!({
                "ok": true,
                "sessionsRoot": root.display().to_string(),
                "stateDir": state_dir(&args).display().to_string(),
                "before": {"sessions": before.0, "rows": before.1},
                "after": {"sessions": index.session_count(), "rows": index.row_count()},
                "rebuilt": index.rebuilds,
                "tookMs": ms,
            });
            println!("{}", serde_json::to_string_pretty(&out).unwrap_or_default());
            ExitCode::SUCCESS
        }
        "serve" => {
            let Some(port) = args.flag("port").and_then(|p| p.parse::<u16>().ok()) else {
                eprintln!("usage: rc-core serve --port N [--sessions-root DIR] [--state-dir DIR]");
                return ExitCode::FAILURE;
            };
            let interval = args
                .flag("interval-secs")
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(60);
            match server::serve(
                port,
                state_dir(&args),
                sessions_root(&args),
                interval,
                args.flag("secret").map(|s| s.to_string()),
            ) {
                Ok(()) => ExitCode::SUCCESS,
                Err(e) => {
                    eprintln!("[rc-core] fatal: {e}");
                    ExitCode::FAILURE
                }
            }
        }
        "query" => {
            let Some(q) = args.positional.first().cloned() else {
                eprintln!("usage: rc-core query <q> [--limit N] [--sessionId ID]");
                return ExitCode::FAILURE;
            };
            let limit = args
                .flag("limit")
                .and_then(|v| v.parse::<usize>().ok())
                .unwrap_or(20);
            let session_id = args.flag("sessionId").map(|s| s.to_string());
            let mut index = build_index(&args);
            if !args.switch("skip-refresh") {
                index.refresh(&sessions_root(&args));
            }
            let t0 = std::time::Instant::now();
            let out = index.query(&q, limit, session_id.as_deref());
            let out = json!({"q": q, "tookMs": t0.elapsed().as_millis(), "result": out});
            println!("{}", serde_json::to_string_pretty(&out).unwrap_or_default());
            ExitCode::SUCCESS
        }
        "bench" => {
            let passes = args
                .flag("passes")
                .and_then(|v| v.parse::<usize>().ok())
                .unwrap_or(3);
            let root = sessions_root(&args);
            let mut results: Vec<serde_json::Value> = Vec::new();
            for pass in 0..passes {
                // A fresh index each pass forces the full heavy path (walk +
                // decode + extract), which is exactly what we want to measure.
                let mut index = Index::new(&state_dir(&args));
                let ms = index.refresh(&root);
                results.push(json!({
                    "pass": pass,
                    "tookMs": ms,
                    "sessions": index.session_count(),
                    "rows": index.row_count(),
                }));
            }
            let out = json!({
                "sessionsRoot": root.display().to_string(),
                "results": results,
            });
            println!("{}", serde_json::to_string_pretty(&out).unwrap_or_default());
            ExitCode::SUCCESS
        }
        _ => {
            println!("rc-core {} — out-of-process heavy work for the RC gateway", env!("CARGO_PKG_VERSION"));
            println!("commands: index | serve --port N | query <q> | bench");
            ExitCode::SUCCESS
        }
    }
}
