//! `cpurest-core`: the shared-memory transport underneath `cpurest`.
//!
//! Layered as: [`shm`] (raw POSIX mapping) -> [`ring`] (one directional
//! mailbox over a mapping) -> [`bus`] (two ring channels = one named bus).
//! Application-facing routing, JSON extraction, and error mapping live in
//! the `cpurest` crate, which depends on this one.

pub mod bus;
pub mod header;
pub mod ring;
pub mod route;
pub mod shm;
pub mod sync;

pub use bus::Bus;
pub use header::{Method, SlotState, BUS_SIZE, CHANNEL_SIZE, PAYLOAD_CAPACITY};
pub use inventory;
pub use ring::{RingChannel, WireMessage};
pub use route::RouteEntry;
pub use shm::ShmRegion;
