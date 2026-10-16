//! Event -> searchable-row extraction.
//!
//! This is a faithful port of the text semantics that the JavaScript gateway
//! used (`pc-gateway/lib/dsh.mjs::eventsToMessages` + `preview`), restricted to
//! what the search index actually stores:
//!
//!   row = [seq, role, ts, text]      (text is whitespace-collapsed, capped)
//!
//! Only the message kinds that carry user-visible text are indexed; turn/step/
//! session meta events are skipped, exactly like the JS version.

use serde_json::Value;

/// One indexed row. `text` is already previewed (collapsed + capped).
#[derive(Debug, Clone)]
pub struct Row {
    pub seq: i64,
    pub role: String,
    pub ts: i64,
    pub text: String,
}

/// Collapse every whitespace run into a single space, trim, then cap.
///
/// Mirrors `preview(text, max)`: `String(text).replace(/\s+/g, ' ').trim()`
/// followed by `slice(0, max)`.
pub fn preview(text: &str, max: usize) -> String {
    let collapsed: String = text.split_whitespace().collect::<Vec<_>>().join(" ");
    if collapsed.chars().count() > max {
        collapsed.chars().take(max).collect()
    } else {
        collapsed
    }
}

/// Join a content-block list: text blocks contribute their text, every other
/// block type contributes `[type]` (same as the JS `map(...).join('')`).
fn join_blocks(blocks: Option<&Value>) -> String {
    let Some(Value::Array(items)) = blocks else {
        return String::new();
    };
    let mut out = String::new();
    for block in items {
        let ty = block.get("type").and_then(Value::as_str).unwrap_or("");
        if ty == "text" {
            if let Some(t) = block.get("text").and_then(Value::as_str) {
                out.push_str(t);
            }
        } else if !ty.is_empty() {
            out.push('[');
            out.push_str(ty);
            out.push(']');
        }
    }
    out
}

/// Extract indexable rows from a batch of events.
///
/// `cap_rows` bounds the per-session row count (defensive memory guard).
pub fn extract_rows(events: &[Value], cap_rows: usize, text_cap: usize) -> Vec<Row> {
    let mut out: Vec<Row> = Vec::new();
    for ev in events {
        if out.len() >= cap_rows {
            break;
        }
        let Some(ty) = ev.get("type").and_then(Value::as_str) else {
            continue;
        };
        let seq = ev.get("seq").and_then(Value::as_i64).unwrap_or(0);
        let ts = ev.get("time").and_then(Value::as_i64).unwrap_or(0);
        let data = ev.get("data").cloned().unwrap_or(Value::Null);

        let (role, raw): (&str, String) = match ty {
            "user/message" => {
                // source.kind !== 'user' is injected context -> role 'system'.
                let kind = data
                    .get("source")
                    .and_then(|s| s.get("kind"))
                    .and_then(Value::as_str)
                    .unwrap_or("");
                let role = if kind == "user" { "user" } else { "system" };
                (role, join_blocks(data.get("content")))
            }
            "assistant/message" => {
                let content = data
                    .get("message")
                    .and_then(|m| m.get("content"))
                    .cloned()
                    .unwrap_or(Value::Array(vec![]));
                let mut body = String::new();
                if let Value::Array(items) = &content {
                    for block in items {
                        let bty = block.get("type").and_then(Value::as_str).unwrap_or("");
                        if bty == "reasoning" || bty == "image" {
                            continue;
                        }
                        if let Some(t) = block.get("text").and_then(Value::as_str) {
                            body.push_str(t);
                        }
                    }
                }
                // JS fallback: a reasoning-only assistant message is still indexed.
                if body.is_empty() {
                    body.push_str("[reasoning]");
                }
                ("assistant", body)
            }
            "system/message" => (
                "system",
                join_blocks(data.get("message").and_then(|m| m.get("content"))),
            ),
            "tool/call" => {
                let name = data.get("name").and_then(Value::as_str).unwrap_or("unknown");
                ("tool", format!("[tool] {}", name))
            }
            // Tool results are summarized as a constant marker (the body lives in
            // a collapsible field the index does not store).
            "tool/result" => ("tool", "[tool] result".to_string()),
            _ => continue,
        };

        let text = preview(&raw, text_cap);
        if text.is_empty() {
            continue;
        }
        out.push(Row {
            seq,
            role: role.to_string(),
            ts,
            text,
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn user_message_uses_text_blocks_and_marks_other_blocks() {
        let ev = json!({
            "type": "user/message", "seq": 7, "time": 1700,
            "data": { "source": {"kind": "user"},
                      "content": [{"type":"text","text":"hello  world"},
                                  {"type":"image","attachment":{"attachmentId":"a"}}] }
        });
        let rows = extract_rows(&[ev], 100, 1500);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].role, "user");
        assert_eq!(rows[0].seq, 7);
        assert_eq!(rows[0].text, "hello world[image]");
    }

    #[test]
    fn injected_context_becomes_system_role() {
        let ev = json!({
            "type": "user/message", "seq": 1, "time": 1,
            "data": { "source": {"kind": "agent-instructions"},
                      "content": [{"type":"text","text":"rules"}] }
        });
        let rows = extract_rows(&[ev], 100, 1500);
        assert_eq!(rows[0].role, "system");
    }

    #[test]
    fn assistant_skips_reasoning_and_falls_back() {
        let with_text = json!({
            "type": "assistant/message", "seq": 2, "time": 2,
            "data": { "message": { "content": [
                {"type":"reasoning","text":"thinking"},
                {"type":"text","text":"answer"}] } }
        });
        let rows = extract_rows(&[with_text], 100, 1500);
        assert_eq!(rows[0].text, "answer");

        let only_reasoning = json!({
            "type": "assistant/message", "seq": 3, "time": 3,
            "data": { "message": { "content": [{"type":"reasoning","text":"x"}] } }
        });
        let rows = extract_rows(&[only_reasoning], 100, 1500);
        assert_eq!(rows[0].text, "[reasoning]");
    }

    #[test]
    fn tool_call_and_result_rows() {
        let evs = vec![
            json!({"type":"tool/call","seq":4,"time":4,"data":{"name":"pwsh"}}),
            json!({"type":"tool/result","seq":5,"time":5,"data":{"message":{}}}),
        ];
        let rows = extract_rows(&evs, 100, 1500);
        assert_eq!(rows[0].text, "[tool] pwsh");
        assert_eq!(rows[1].text, "[tool] result");
    }

    #[test]
    fn meta_events_are_skipped() {
        let evs = vec![json!({"type":"turn/start","seq":1,"time":1,"data":{}})];
        assert!(extract_rows(&evs, 100, 1500).is_empty());
    }

    #[test]
    fn preview_collapses_and_caps() {
        assert_eq!(preview("  a\n\n b\tc ", 100), "a b c");
        assert_eq!(preview("abcdef", 3), "abc");
    }

    #[test]
    fn row_cap_is_enforced() {
        let evs: Vec<Value> = (0..10)
            .map(|i| json!({"type":"tool/result","seq":i,"time":i,"data":{"message":{}}}))
            .collect();
        assert_eq!(extract_rows(&evs, 4, 1500).len(), 4);
    }
}
