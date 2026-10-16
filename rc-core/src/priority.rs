//! Self-throttling: make the sidecar yield to the process it serves.
//!
//! The whole point of this binary is that heavy indexing must never compete with
//! the DSH process. Moving the work out of DSH removes event-loop blocking, but
//! the OS scheduler can still give a busy CPU-bound process a slice that shows up
//! as a latency blip in DSH (measured: 4 samples > 50 ms out of 17,509 during a
//! full rebuild). Dropping our own priority class makes DSH win every contest.
//!
//! Windows-only FFI on purpose: no external crate, no runtime, just two kernel32
//! calls. On other platforms this is a no-op.

#[cfg(windows)]
mod win {
    #[link(name = "kernel32")]
    extern "system" {
        fn GetCurrentProcess() -> isize;
        fn SetPriorityClass(handle: isize, class: u32) -> i32;
    }

    pub fn set_class(class: u32) -> bool {
        unsafe { SetPriorityClass(GetCurrentProcess(), class) != 0 }
    }
}

const BELOW_NORMAL_PRIORITY_CLASS: u32 = 0x0000_4000;
const IDLE_PRIORITY_CLASS: u32 = 0x0000_0040;

/// Lower this process's priority. Returns the class that was applied.
///
/// `level`: "below-normal" (default) or "idle".
pub fn lower_self(level: &str) -> &'static str {
    #[cfg(windows)]
    {
        let (class, name) = if level.eq_ignore_ascii_case("idle") {
            (IDLE_PRIORITY_CLASS, "idle")
        } else {
            (BELOW_NORMAL_PRIORITY_CLASS, "below-normal")
        };
        return if win::set_class(class) { name } else { "unchanged" };
    }
    #[cfg(not(windows))]
    {
        let _ = level;
        "unsupported"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_known_levels_without_panicking() {
        // Runs in-process: this genuinely lowers the *test* process priority,
        // which is harmless and proves the FFI call is wired up.
        let applied = lower_self("below-normal");
        assert!(
            applied == "below-normal" || applied == "unchanged" || applied == "unsupported",
            "unexpected result: {applied}"
        );
        assert_eq!(lower_self("idle"), if cfg!(windows) { "idle" } else { "unsupported" });
    }
}
