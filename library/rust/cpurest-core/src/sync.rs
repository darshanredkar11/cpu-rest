//! Hybrid spin/yield/park wait on the header's atomic state flag.
//!
//! The *writer* claims a slot transition with a real CAS (cheap correctness
//! insurance, and it's what the spec asks for even though there's only one
//! writer per channel). The *reader* just needs to observe a value, so it
//! spins on plain acquire-loads: a tight busy-spin for the first ~500ns
//! (the common case — the peer is usually already done or about to be),
//! then cooperative yielding, then short parked sleeps if the peer is slow
//! or stalled, so an idle bus doesn't peg a core at 100%.

use std::fmt;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{Duration, Instant};

/// Roughly 500ns worth of spin iterations on typical modern hardware.
/// Not load-bearing for correctness, only for how quickly phase 2 kicks in.
const TIGHT_SPIN_ITERS: u32 = 2000;
const YIELD_ITERS: u32 = 200;
const PARK_SLEEP: Duration = Duration::from_micros(50);

#[derive(Debug)]
pub struct TimeoutError;

impl fmt::Display for TimeoutError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "timed out waiting on cpurest shared-memory bus")
    }
}
impl std::error::Error for TimeoutError {}

/// View the 4 bytes at `ptr` as an `AtomicU32`. `ptr` must be valid,
/// 4-byte aligned, and live for as long as the returned reference is used
/// (true for the lifetime of the owning `ShmRegion` mapping).
#[inline]
pub unsafe fn flag_at<'a>(ptr: *mut u8) -> &'a AtomicU32 {
    &*(ptr as *const AtomicU32)
}

/// Attempt the `from -> to` transition. `true` iff this call performed it.
#[inline]
pub fn try_claim(flag: &AtomicU32, from: u32, to: u32) -> bool {
    flag.compare_exchange(from, to, Ordering::AcqRel, Ordering::Relaxed)
        .is_ok()
}

/// Reset the flag with release ordering, publishing any header/payload
/// writes made since the caller observed the prior state.
#[inline]
pub fn publish(flag: &AtomicU32, state: u32) {
    flag.store(state, Ordering::Release);
}

/// Bounded busy-spin for up to `spins` iterations. Returns `true` as soon
/// as `flag == want`. Never sleeps or yields — safe to call from inside an
/// async `Future::poll`.
#[inline]
pub fn poll_spin(flag: &AtomicU32, want: u32, spins: u32) -> bool {
    for _ in 0..spins {
        if flag.load(Ordering::Acquire) == want {
            return true;
        }
        std::hint::spin_loop();
    }
    false
}

/// Blocking wait until `flag == want` or `timeout` elapses. Escalates
/// spin -> yield -> park-sleep. Intended for the dedicated server thread,
/// which has nothing better to do while waiting.
pub fn spin_wait(flag: &AtomicU32, want: u32, timeout: Duration) -> Result<(), TimeoutError> {
    let deadline = Instant::now() + timeout;

    if poll_spin(flag, want, TIGHT_SPIN_ITERS) {
        return Ok(());
    }
    for _ in 0..YIELD_ITERS {
        if flag.load(Ordering::Acquire) == want {
            return Ok(());
        }
        std::thread::yield_now();
        if Instant::now() >= deadline {
            return Err(TimeoutError);
        }
    }
    loop {
        if flag.load(Ordering::Acquire) == want {
            return Ok(());
        }
        if Instant::now() >= deadline {
            return Err(TimeoutError);
        }
        std::thread::sleep(PARK_SLEEP);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn claim_publish_roundtrip() {
        let flag = AtomicU32::new(0);
        assert!(try_claim(&flag, 0, 1));
        assert!(!try_claim(&flag, 0, 1), "second claim from IDLE must fail");
        assert_eq!(flag.load(Ordering::Acquire), 1);
        publish(&flag, 0);
        assert_eq!(flag.load(Ordering::Acquire), 0);
    }

    #[test]
    fn spin_wait_observes_concurrent_publish() {
        use std::sync::Arc;
        let flag = Arc::new(AtomicU32::new(0));
        let f2 = flag.clone();
        let h = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(5));
            publish(&f2, 1);
        });
        spin_wait(&flag, 1, Duration::from_secs(1)).expect("should observe publish");
        h.join().unwrap();
    }

    #[test]
    fn spin_wait_times_out() {
        let flag = AtomicU32::new(0);
        let res = spin_wait(&flag, 1, Duration::from_millis(20));
        assert!(res.is_err());
    }
}
