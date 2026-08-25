//! A `Bus` is a named 1 MiB shared-memory segment split into two
//! [`RingChannel`]s. Whichever side calls [`Bus::create_server`] **creates**
//! (and owns/cleans up) the segment; channel roles are fixed relative to
//! that creator, not to which language is on which side:
//!
//! - Channel 1 (offset 0): request, written by the client, read by the server.
//! - Channel 2 (offset `CHANNEL_SIZE`): response, written by the server, read by the client.
//!
//! This is what makes the same `Bus` type work whether Rust or Java is the
//! server on a given bus name.

use crate::header::{BUS_SIZE, CHANNEL_SIZE};
use crate::ring::RingChannel;
use crate::shm::ShmRegion;
use std::io;
use std::time::Duration;

pub struct Bus {
    // Held for its Drop impl (unmaps, and unlinks if we're the owner);
    // never read through directly after construction.
    _region: ShmRegion,
    request: RingChannel,
    response: RingChannel,
}

impl Bus {
    /// Create and own the named bus. This side is the server for it.
    pub fn create_server(name: &str) -> io::Result<Self> {
        let region = ShmRegion::create(name, BUS_SIZE)?;
        Self::from_region(region)
    }

    /// Connect to a bus created by the peer. This side is the client.
    /// Retries until the server has created the segment, or `timeout` elapses.
    pub fn connect_client(name: &str, timeout: Duration) -> io::Result<Self> {
        let region = ShmRegion::open_existing(name, BUS_SIZE, timeout)?;
        Self::from_region(region)
    }

    fn from_region(region: ShmRegion) -> io::Result<Self> {
        let base = region.as_ptr();
        // SAFETY: `region` maps at least BUS_SIZE = 2 * CHANNEL_SIZE bytes,
        // and outlives both channels (they only read through raw pointers
        // into its mapping, never depend on ShmRegion's own address).
        let request = unsafe { RingChannel::new(base) };
        let response = unsafe { RingChannel::new(base.add(CHANNEL_SIZE)) };
        Ok(Self { _region: region, request, response })
    }

    #[inline]
    pub fn request_channel(&self) -> &RingChannel {
        &self.request
    }

    #[inline]
    pub fn response_channel(&self) -> &RingChannel {
        &self.response
    }
}
