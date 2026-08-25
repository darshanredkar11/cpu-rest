//! Typed client: `client.post::<Req, Resp>("/path", &payload).await`.
//!
//! `roundtrip` writes into the request channel, then awaits the response
//! via a hand-rolled `Future` that does a bounded busy-spin per `poll` and
//! otherwise returns `Pending` + immediately re-wakes — so the wait is real
//! cooperative yielding to whatever executor is driving it (including the
//! host application's own tokio runtime), not a thread-blocking spin.

use crate::error::CpuError;
use cpurest_core::{Bus, Method, RingChannel, SlotState};
use serde::de::DeserializeOwned;
use serde::Serialize;
use std::future::poll_fn;
use std::sync::atomic::{AtomicBool, Ordering};
use std::task::Poll;
use std::time::{Duration, Instant};

const WRITE_TIMEOUT: Duration = Duration::from_secs(5);
const RESPONSE_TIMEOUT: Duration = Duration::from_secs(5);
/// Busy-spin iterations attempted per `poll()` call before yielding back to
/// the executor. Keeps each poll bounded and cheap regardless of how the
/// host schedules this task.
const POLL_SPINS: u32 = 512;

pub struct Client {
    bus: Bus,
    // A bus is a single-slot mailbox per direction: two concurrent calls on
    // the same Client would otherwise overwrite each other's request/response
    // slot mid-flight. This serializes calls without requiring a full async
    // runtime's mutex (and without adding a hard tokio dependency).
    call_lock: AsyncSpinLock,
}

impl Client {
    /// Connect to a bus created by a [`crate::Server`], retrying for up to
    /// 5 seconds if the server hasn't started yet.
    pub fn connect(bus_name: &str) -> std::io::Result<Self> {
        Self::connect_timeout(bus_name, Duration::from_secs(5))
    }

    pub fn connect_timeout(bus_name: &str, timeout: Duration) -> std::io::Result<Self> {
        let bus = Bus::connect_client(bus_name, timeout)?;
        Ok(Self { bus, call_lock: AsyncSpinLock::new() })
    }

    pub async fn get<Resp: DeserializeOwned>(&self, path: &str) -> Result<Resp, CpuError> {
        self.roundtrip(Method::Get, path, &[]).await
    }

    pub async fn post<Req: Serialize, Resp: DeserializeOwned>(&self, path: &str, body: &Req) -> Result<Resp, CpuError> {
        self.roundtrip(Method::Post, path, &to_bytes(body)?).await
    }

    pub async fn put<Req: Serialize, Resp: DeserializeOwned>(&self, path: &str, body: &Req) -> Result<Resp, CpuError> {
        self.roundtrip(Method::Put, path, &to_bytes(body)?).await
    }

    pub async fn delete<Resp: DeserializeOwned>(&self, path: &str) -> Result<Resp, CpuError> {
        self.roundtrip(Method::Delete, path, &[]).await
    }

    async fn roundtrip<Resp: DeserializeOwned>(&self, method: Method, path: &str, payload: &[u8]) -> Result<Resp, CpuError> {
        let _guard = self.call_lock.lock().await;

        let request_channel = self.bus.request_channel();
        let response_channel = self.bus.response_channel();

        request_channel
            .write(SlotState::ReqReady as u32, method, 0, path, payload, WRITE_TIMEOUT)
            .map_err(|e| CpuError::internal(format!("failed to send request: {e}")))?;

        let msg = wait_for_response(response_channel).await?;
        response_channel.consume();

        if (200..300).contains(&msg.status) {
            serde_json::from_slice(&msg.payload)
                .map_err(|e| CpuError::internal(format!("failed to decode response JSON: {e}")))
        } else {
            Err(CpuError::new(msg.status, extract_error_message(&msg.payload)))
        }
    }
}

fn to_bytes<Req: Serialize>(body: &Req) -> Result<Vec<u8>, CpuError> {
    serde_json::to_vec(body).map_err(|e| CpuError::bad_request(format!("failed to encode request: {e}")))
}

fn extract_error_message(payload: &[u8]) -> String {
    serde_json::from_slice::<serde_json::Value>(payload)
        .ok()
        .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(str::to_owned))
        .unwrap_or_else(|| String::from_utf8_lossy(payload).into_owned())
}

async fn wait_for_response(channel: &RingChannel) -> Result<cpurest_core::WireMessage, CpuError> {
    let deadline = Instant::now() + RESPONSE_TIMEOUT;
    poll_fn(|cx| {
        if let Some(msg) = channel.try_read(SlotState::RespReady as u32, POLL_SPINS) {
            return Poll::Ready(Ok(msg));
        }
        if Instant::now() >= deadline {
            return Poll::Ready(Err(CpuError::internal("timed out waiting for response")));
        }
        cx.waker().wake_by_ref();
        Poll::Pending
    })
    .await
}

struct AsyncSpinLock(AtomicBool);

impl AsyncSpinLock {
    fn new() -> Self {
        Self(AtomicBool::new(false))
    }

    async fn lock(&self) -> SpinLockGuard<'_> {
        poll_fn(|cx| {
            if self.0.compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed).is_ok() {
                Poll::Ready(())
            } else {
                cx.waker().wake_by_ref();
                Poll::Pending
            }
        })
        .await;
        SpinLockGuard(self)
    }
}

struct SpinLockGuard<'a>(&'a AsyncSpinLock);

impl Drop for SpinLockGuard<'_> {
    fn drop(&mut self) {
        self.0 .0.store(false, Ordering::Release);
    }
}
