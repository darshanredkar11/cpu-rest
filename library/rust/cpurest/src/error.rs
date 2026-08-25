use crate::extract::{encode_error, IntoCpuResponse};
use std::fmt;

/// An HTTP-style error: a status code plus a message, JSON-encoded as
/// `{"error": message}` on the wire. Handlers return
/// `Result<Json<T>, CpuError>`; panics inside a handler are caught by the
/// server and mapped to `CpuError::internal("handler panicked")` so a bug
/// in one route never takes down the whole shared-memory bus.
#[derive(Debug, Clone)]
pub struct CpuError {
    pub status: u16,
    pub message: String,
}

impl CpuError {
    pub fn new(status: u16, message: impl Into<String>) -> Self {
        Self { status, message: message.into() }
    }

    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::new(400, message)
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(404, message)
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(500, message)
    }
}

impl fmt::Display for CpuError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} {}", self.status, self.message)
    }
}

impl std::error::Error for CpuError {}

impl IntoCpuResponse for CpuError {
    fn into_response(self) -> (u16, Vec<u8>) {
        (self.status, encode_error(&self.message))
    }
}
