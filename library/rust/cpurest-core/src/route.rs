//! Compile-time route registry. `cpurest-macros` emits an
//! `inventory::submit!` for every `#[cpu_get]`/`#[cpu_post]`/... handler in
//! the binary; [`Server::mount`](../../cpurest/struct.Server.html) in the
//! `cpurest` crate iterates `inventory::iter::<RouteEntry>()` to build its
//! dispatch table without any hand-written registration list.

use crate::header::Method;
use std::future::Future;
use std::pin::Pin;

pub type HandlerFn = fn(&[u8]) -> Pin<Box<dyn Future<Output = (u16, Vec<u8>)> + Send>>;

pub struct RouteEntry {
    pub method: Method,
    pub path: &'static str,
    pub handler: HandlerFn,
}

inventory::collect!(RouteEntry);
