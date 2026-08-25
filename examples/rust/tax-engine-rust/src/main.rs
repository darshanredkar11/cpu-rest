//! Example `cpurest` server: exposes `POST /calculate` on bus `/tax_engine`.
//! This is the Rust side of the polyglot demo — `java-backend-demo` (in
//! `examples/java/`) calls this over shared memory via a `@CpuClient`.

use cpurest::{cpu_post, CpuError, Json, Server};
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

#[derive(Debug, Deserialize)]
struct TaxRequest {
    income: f64,
    deductions: f64,
}

#[derive(Debug, Serialize)]
struct TaxResponse {
    tax_owed: f64,
    effective_rate: f64,
}

/// Illustrative 2023 US single-filer bracket table — a stand-in payload
/// realistic enough to be worth serializing, not tax advice.
const BRACKETS: &[(f64, f64)] = &[
    (11_000.0, 0.10),
    (44_725.0, 0.12),
    (95_375.0, 0.22),
    (182_100.0, 0.24),
    (231_250.0, 0.32),
    (578_125.0, 0.35),
    (f64::INFINITY, 0.37),
];

fn bracket_tax(taxable: f64) -> f64 {
    let mut tax = 0.0;
    let mut prev_upper = 0.0;
    for &(upper, rate) in BRACKETS {
        if taxable <= prev_upper {
            break;
        }
        let amount_in_bracket = (taxable.min(upper) - prev_upper).max(0.0);
        tax += amount_in_bracket * rate;
        prev_upper = upper;
    }
    tax
}

#[cpu_post("/calculate")]
async fn calculate(Json(req): Json<TaxRequest>) -> Result<Json<TaxResponse>, CpuError> {
    if req.income < 0.0 || req.deductions < 0.0 {
        return Err(CpuError::bad_request("income and deductions must be non-negative"));
    }
    let taxable = (req.income - req.deductions).max(0.0);
    let tax_owed = bracket_tax(taxable);
    let effective_rate = if req.income > 0.0 { tax_owed / req.income } else { 0.0 };
    Ok(Json(TaxResponse { tax_owed, effective_rate }))
}

fn main() -> std::io::Result<()> {
    let server = Server::bind("/tax_engine")?;
    eprintln!(
        "tax-engine-rust: mounted {} route(s) on bus \"/tax_engine\"",
        server.route_count()
    );
    let handle = server.serve();

    let running = Arc::new(AtomicBool::new(true));
    let running_in_handler = running.clone();
    ctrlc::set_handler(move || running_in_handler.store(false, Ordering::Relaxed))
        .expect("failed to install signal handler");

    eprintln!("tax-engine-rust: serving. Ctrl+C to stop.");
    while running.load(Ordering::Relaxed) {
        std::thread::sleep(Duration::from_millis(100));
    }
    eprintln!("tax-engine-rust: shutting down...");
    handle.stop();
    Ok(())
}
