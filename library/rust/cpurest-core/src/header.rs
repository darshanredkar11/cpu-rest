//! Wire layout for one direction of a `cpurest` bus.
//!
//! Deliberately 3 cache lines (192B), not a literal 64B struct: the state
//! flag is the only field under contention (both sides poll it), so it gets
//! an entire cache line to itself to avoid false-sharing with the cold
//! method/status/route/length fields. This must stay byte-identical with
//! the Java side's `MemoryLayout` in `HeaderLayout.java`.

pub const CACHE_LINE: usize = 64;

/// Offset of the atomic state flag (u32), alone on its own cache line.
pub const FLAG_OFFSET: usize = 0;
/// Offset of the HTTP method byte (u8).
pub const METHOD_OFFSET: usize = CACHE_LINE;
/// Offset of the status code (u16).
pub const STATUS_OFFSET: usize = METHOD_OFFSET + 2;
/// Offset of the payload length (u32).
pub const LEN_OFFSET: usize = METHOD_OFFSET + 4;
/// Offset of the null-terminated route buffer (64B), on its own cache line.
pub const ROUTE_OFFSET: usize = CACHE_LINE * 2;
/// Length in bytes of the route buffer.
pub const ROUTE_BUF_LEN: usize = 64;
/// Offset where the dynamic JSON payload begins.
pub const HEADER_SIZE: usize = CACHE_LINE * 3;

/// Total size of one channel (header + payload capacity), 512 KiB.
pub const CHANNEL_SIZE: usize = 512 * 1024;
/// Bytes available to the JSON payload within a channel.
pub const PAYLOAD_CAPACITY: usize = CHANNEL_SIZE - HEADER_SIZE;

/// Total size of a bus: two channels back to back, 1 MiB.
pub const BUS_SIZE: usize = CHANNEL_SIZE * 2;

/// Slot state, stored as a `u32` at [`FLAG_OFFSET`].
#[repr(u32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SlotState {
    Idle = 0,
    ReqReady = 1,
    RespReady = 2,
}

impl SlotState {
    #[inline]
    pub fn from_u32(v: u32) -> Option<Self> {
        match v {
            0 => Some(Self::Idle),
            1 => Some(Self::ReqReady),
            2 => Some(Self::RespReady),
            _ => None,
        }
    }
}

/// HTTP-style method carried in the header.
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Method {
    Get = 0,
    Post = 1,
    Put = 2,
    Delete = 3,
}

impl Method {
    #[inline]
    pub fn from_u8(v: u8) -> Option<Self> {
        match v {
            0 => Some(Self::Get),
            1 => Some(Self::Post),
            2 => Some(Self::Put),
            3 => Some(Self::Delete),
            _ => None,
        }
    }

    #[inline]
    pub fn as_str(&self) -> &'static str {
        match self {
            Method::Get => "GET",
            Method::Post => "POST",
            Method::Put => "PUT",
            Method::Delete => "DELETE",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offsets_are_cache_line_aligned_and_non_overlapping() {
        assert_eq!(FLAG_OFFSET, 0);
        assert_eq!(METHOD_OFFSET, 64);
        assert_eq!(STATUS_OFFSET, 66);
        assert_eq!(LEN_OFFSET, 68);
        assert_eq!(ROUTE_OFFSET, 128);
        assert_eq!(HEADER_SIZE, 192);
        // route buffer must fit entirely before the payload starts
        assert!(ROUTE_OFFSET + ROUTE_BUF_LEN <= HEADER_SIZE);
        // method/status/len must fit within cache line 1 before route's cache line
        assert!(LEN_OFFSET + 4 <= ROUTE_OFFSET);
    }

    #[test]
    fn channel_and_bus_sizes() {
        assert_eq!(CHANNEL_SIZE, 512 * 1024);
        assert_eq!(BUS_SIZE, 1024 * 1024);
        assert_eq!(PAYLOAD_CAPACITY, CHANNEL_SIZE - 192);
    }
}
