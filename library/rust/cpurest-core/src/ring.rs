//! `RingChannel`: one direction of a bus.
//!
//! Despite the name (kept for parity with the spec's vocabulary and to
//! leave room for a future multi-slot pipelined ring), this is a **single
//! slot SPSC mailbox**: `IDLE -> {REQ,RESP}_READY -> IDLE`. Every usage
//! pattern in scope (`client.post(...).await`) is one in-flight message at
//! a time per channel, so a true multi-slot lock-free ring buffer would add
//! sequence numbers, wraparound, and producer/consumer cursors for no
//! benefit yet. See `docs/PRD.md` for the multi-slot pipelining follow-up.
//!
//! Multi-byte header fields are native-endian: both endpoints run on the
//! same physical host, so there's no need to pay for byte-swapping, but it
//! does mean this wire format is not portable across a byte-swapped pair of
//! machines (out of scope for shared-memory IPC, which is host-local by
//! definition).

use crate::header::*;
use crate::sync::{self, TimeoutError};
use std::io;
use std::sync::atomic::AtomicU32;
use std::time::Duration;

pub struct WireMessage {
    pub method: Method,
    pub status: u16,
    pub route: String,
    pub payload: Vec<u8>,
}

/// One direction of a bus: a `CHANNEL_SIZE`-byte region starting with the
/// header described in `header.rs`, followed by the payload buffer.
pub struct RingChannel {
    base: *mut u8,
}

// SAFETY: all access is gated by the atomic state flag at the start of the
// header, which provides the acquire/release synchronization needed to
// hand the region off between the writer and reader threads/processes.
unsafe impl Send for RingChannel {}
unsafe impl Sync for RingChannel {}

impl RingChannel {
    /// `base` must point to a valid, live `CHANNEL_SIZE`-byte region
    /// (a slice of an `ShmRegion`'s mapping) for as long as this value is used.
    pub unsafe fn new(base: *mut u8) -> Self {
        Self { base }
    }

    #[inline]
    fn flag(&self) -> &AtomicU32 {
        unsafe { sync::flag_at(self.base) }
    }

    /// Block (with hybrid spin/yield/park) until the flag reads `IDLE`,
    /// write the message, then CAS `IDLE -> ready_state` to publish it.
    pub fn write(
        &self,
        ready_state: u32,
        method: Method,
        status: u16,
        route: &str,
        payload: &[u8],
        timeout: Duration,
    ) -> io::Result<()> {
        if payload.len() > PAYLOAD_CAPACITY {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!(
                    "payload of {} bytes exceeds channel capacity of {PAYLOAD_CAPACITY} bytes",
                    payload.len()
                ),
            ));
        }
        let route_bytes = route.as_bytes();
        if route_bytes.len() >= ROUTE_BUF_LEN {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("route {route:?} exceeds {ROUTE_BUF_LEN}-byte buffer"),
            ));
        }

        sync::spin_wait(self.flag(), SlotState::Idle as u32, timeout)
            .map_err(|e| io::Error::new(io::ErrorKind::TimedOut, e))?;

        unsafe {
            self.base.add(METHOD_OFFSET).write(method as u8);
            (self.base.add(STATUS_OFFSET) as *mut u16).write_unaligned(status);
            (self.base.add(LEN_OFFSET) as *mut u32).write_unaligned(payload.len() as u32);

            let route_ptr = self.base.add(ROUTE_OFFSET);
            std::ptr::write_bytes(route_ptr, 0, ROUTE_BUF_LEN);
            std::ptr::copy_nonoverlapping(route_bytes.as_ptr(), route_ptr, route_bytes.len());

            std::ptr::copy_nonoverlapping(payload.as_ptr(), self.base.add(HEADER_SIZE), payload.len());
        }

        if !sync::try_claim(self.flag(), SlotState::Idle as u32, ready_state) {
            // Only possible if something else raced us onto this slot, which
            // shouldn't happen in the single-writer protocol this channel is
            // used under; surface it rather than silently overwriting.
            return Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "lost the race to publish onto this channel (concurrent writer?)",
            ));
        }
        Ok(())
    }

    /// Block until the flag reads `ready_state`, then copy out the message.
    /// Does not reset the flag — call [`RingChannel::consume`] once done.
    pub fn wait_and_read(&self, ready_state: u32, timeout: Duration) -> Result<WireMessage, TimeoutError> {
        sync::spin_wait(self.flag(), ready_state, timeout)?;
        Ok(unsafe { self.read_unchecked() })
    }

    /// Non-blocking variant for use inside an async `Future::poll`: does a
    /// bounded spin only, returns `None` if the message isn't ready yet.
    pub fn try_read(&self, ready_state: u32, spins: u32) -> Option<WireMessage> {
        if sync::poll_spin(self.flag(), ready_state, spins) {
            Some(unsafe { self.read_unchecked() })
        } else {
            None
        }
    }

    unsafe fn read_unchecked(&self) -> WireMessage {
        let method = Method::from_u8(self.base.add(METHOD_OFFSET).read()).unwrap_or(Method::Get);
        let status = (self.base.add(STATUS_OFFSET) as *const u16).read_unaligned();
        let len = (self.base.add(LEN_OFFSET) as *const u32).read_unaligned() as usize;

        let route_ptr = self.base.add(ROUTE_OFFSET);
        let route_slice = std::slice::from_raw_parts(route_ptr, ROUTE_BUF_LEN);
        let nul = route_slice.iter().position(|&b| b == 0).unwrap_or(ROUTE_BUF_LEN);
        let route = String::from_utf8_lossy(&route_slice[..nul]).into_owned();

        let len = len.min(PAYLOAD_CAPACITY);
        let payload_slice = std::slice::from_raw_parts(self.base.add(HEADER_SIZE), len);
        let payload = payload_slice.to_vec();

        WireMessage { method, status, route, payload }
    }

    /// Reset the flag to `IDLE`, freeing the slot for the next write.
    #[inline]
    pub fn consume(&self) {
        sync::publish(self.flag(), SlotState::Idle as u32);
    }

    /// Returns `true` iff the flag currently reads `ready_state`, without
    /// spinning at all. Used by the client's cooperative-yield `Future`.
    #[inline]
    pub fn peek(&self, ready_state: u32) -> bool {
        use std::sync::atomic::Ordering;
        self.flag().load(Ordering::Acquire) == ready_state
    }
}
