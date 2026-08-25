//! Typed server: binds a bus, auto-mounts every `#[cpu_get]`/`#[cpu_post]`/
//! `#[cpu_put]`/`#[cpu_delete]` handler registered in the binary (via
//! `cpurest-core`'s `inventory` route registry), and runs the poll loop on
//! a dedicated OS thread so it never blocks the host process.

use crate::extract::encode_error;
use cpurest_core::route::RouteEntry;
use cpurest_core::{Bus, SlotState};
use std::io;
use std::panic::{self, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

/// How long the server's poll loop blocks per iteration before checking
/// whether it's been asked to stop. Not a request timeout — just the
/// server's responsiveness to `ServerHandle::stop()`.
const POLL_SLICE: Duration = Duration::from_millis(200);
const RESPONSE_WRITE_TIMEOUT: Duration = Duration::from_secs(5);

pub struct Server {
    bus: Bus,
    routes: Vec<&'static RouteEntry>,
}

impl Server {
    /// Create (and own) the named bus, and collect every handler registered
    /// anywhere in this binary via `#[cpu_get]`/`#[cpu_post]`/etc.
    pub fn bind(bus_name: &str) -> io::Result<Self> {
        let bus = Bus::create_server(bus_name)?;
        let routes: Vec<&'static RouteEntry> = cpurest_core::inventory::iter::<RouteEntry>().collect();
        Ok(Self { bus, routes })
    }

    /// How many routes were discovered. Useful for a startup log line /
    /// sanity check that your handlers actually got linked in.
    pub fn route_count(&self) -> usize {
        self.routes.len()
    }

    /// Spawn the dedicated background thread and start serving. Returns
    /// immediately; call [`ServerHandle::stop`] (or drop it) to shut down.
    pub fn serve(self) -> ServerHandle {
        let running = Arc::new(AtomicBool::new(true));
        let running_in_thread = running.clone();
        let thread = std::thread::spawn(move || {
            Self::run_loop(self.bus, self.routes, running_in_thread);
        });
        ServerHandle { thread: Some(thread), running }
    }

    fn run_loop(bus: Bus, routes: Vec<&'static RouteEntry>, running: Arc<AtomicBool>) {
        let request_channel = bus.request_channel();
        let response_channel = bus.response_channel();

        while running.load(Ordering::Relaxed) {
            let msg = match request_channel.wait_and_read(SlotState::ReqReady as u32, POLL_SLICE) {
                Ok(msg) => msg,
                Err(_timeout) => continue,
            };

            let route = routes.iter().find(|r| r.method == msg.method && r.path == msg.route);
            let (status, body) = match route {
                Some(entry) => {
                    let handler = entry.handler;
                    match panic::catch_unwind(AssertUnwindSafe(|| futures_executor::block_on(handler(&msg.payload)))) {
                        Ok(response) => response,
                        Err(_panic) => (500, encode_error("handler panicked")),
                    }
                }
                None => (
                    404,
                    encode_error(&format!("no route for {} {}", msg.method.as_str(), msg.route)),
                ),
            };

            request_channel.consume();
            let _ = response_channel.write(
                SlotState::RespReady as u32,
                msg.method,
                status,
                &msg.route,
                &body,
                RESPONSE_WRITE_TIMEOUT,
            );
        }
    }
}

/// Handle to a running [`Server`]. Dropping it stops the server (equivalent
/// to calling [`ServerHandle::stop`]) and joins the background thread.
pub struct ServerHandle {
    thread: Option<JoinHandle<()>>,
    running: Arc<AtomicBool>,
}

impl ServerHandle {
    pub fn stop(mut self) {
        self.stop_inner();
    }

    fn stop_inner(&mut self) {
        self.running.store(false, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

impl Drop for ServerHandle {
    fn drop(&mut self) {
        self.stop_inner();
    }
}
