//! `Json<T>` request/response wrapper and the `IntoCpuResponse` trait that
//! lets `#[cpu_post]` etc. turn a handler's return value into `(status, body)`
//! without the macro needing to parse the return type at all.

use crate::error::CpuError;
use serde::Serialize;

/// Wraps a typed request body (as a handler argument) or response body (as
/// a handler return value), mirroring Axum's `Json<T>` extractor.
pub struct Json<T>(pub T);

pub trait IntoCpuResponse {
    fn into_response(self) -> (u16, Vec<u8>);
}

impl<T: Serialize> IntoCpuResponse for Json<T> {
    fn into_response(self) -> (u16, Vec<u8>) {
        match serde_json::to_vec(&self.0) {
            Ok(bytes) => (200, bytes),
            Err(e) => (500, encode_error(&format!("failed to serialize response: {e}"))),
        }
    }
}

impl<T: Serialize> IntoCpuResponse for Result<Json<T>, CpuError> {
    fn into_response(self) -> (u16, Vec<u8>) {
        match self {
            Ok(json) => json.into_response(),
            Err(e) => e.into_response(),
        }
    }
}

pub fn encode_error(message: &str) -> Vec<u8> {
    serde_json::json!({ "error": message }).to_string().into_bytes()
}
