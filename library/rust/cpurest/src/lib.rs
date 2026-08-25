//! `cpurest`: HTTP/REST ergonomics at shared-memory speed.
//!
//! ```ignore
//! use cpurest::{cpu_post, Json, Server};
//! use serde::{Deserialize, Serialize};
//!
//! #[derive(Deserialize)]
//! struct TaxRequest { income: f64 }
//! #[derive(Serialize)]
//! struct TaxResponse { tax_owed: f64 }
//!
//! #[cpu_post("/calculate")]
//! async fn calculate(Json(req): Json<TaxRequest>) -> Json<TaxResponse> {
//!     Json(TaxResponse { tax_owed: req.income * 0.2 })
//! }
//!
//! # async fn run() -> std::io::Result<()> {
//! let server = Server::bind("/tax_engine")?.serve();
//! # server.stop();
//! # Ok(()) }
//! ```

mod client;
mod error;
mod extract;
mod server;

pub use client::Client;
pub use cpurest_macros::{cpu_delete, cpu_get, cpu_post, cpu_put};
pub use error::CpuError;
pub use extract::Json;
pub use server::{Server, ServerHandle};

/// Not part of the public API — referenced by generated code from
/// `cpurest-macros` via `::cpurest::__private::...` so that a crate which
/// only depends on `cpurest` (not `cpurest-core` directly) still resolves
/// the paths the macro expands to.
#[doc(hidden)]
pub mod __private {
    pub use crate::extract::{encode_error, IntoCpuResponse};
    pub use cpurest_core;
    pub use serde_json;
}
