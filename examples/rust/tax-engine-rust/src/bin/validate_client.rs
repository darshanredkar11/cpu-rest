//! Demonstrates the *other* direction: Rust as the `cpurest` client, calling
//! into a Java-hosted `@CpuRestController` on bus `/java_backend`. Run the
//! Java demo first (it hosts this controller in a background daemon thread
//! alongside its own client calls into `tax-engine-rust`), then run this
//! binary: `cargo run --bin validate_client`.

use cpurest::Client;
use serde::{Deserialize, Serialize};
use std::time::Instant;

#[derive(Serialize)]
struct ValidateRequest {
    income: f64,
}

#[derive(Deserialize)]
struct ValidateResponse {
    valid: bool,
    reason: Option<String>,
}

const WARMUP_CALLS: usize = 200;
const TIMED_CALLS: usize = 2000;

fn main() -> std::io::Result<()> {
    let client = Client::connect("/java_backend")?;
    let request = ValidateRequest { income: 85_000.0 };

    let response: ValidateResponse =
        futures_executor::block_on(client.post("/validate", &request)).map_err(|e| std::io::Error::other(e.to_string()))?;
    println!(
        "Rust -> Java bus \"/java_backend\" POST /validate => valid={} reason={:?}",
        response.valid, response.reason
    );

    println!(
        "validate_client: benchmarking round-trip latency ({WARMUP_CALLS} warmup + {TIMED_CALLS} timed calls)..."
    );
    for _ in 0..WARMUP_CALLS {
        let _: ValidateResponse = futures_executor::block_on(client.post("/validate", &request))
            .map_err(|e| std::io::Error::other(e.to_string()))?;
    }
    let mut samples_us = Vec::with_capacity(TIMED_CALLS);
    for _ in 0..TIMED_CALLS {
        let start = Instant::now();
        let _: ValidateResponse = futures_executor::block_on(client.post("/validate", &request))
            .map_err(|e| std::io::Error::other(e.to_string()))?;
        samples_us.push(start.elapsed().as_secs_f64() * 1_000_000.0);
    }
    samples_us.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let min = samples_us[0];
    let p50 = samples_us[samples_us.len() / 2];
    let p99 = samples_us[(samples_us.len() as f64 * 0.99) as usize];
    let mean = samples_us.iter().sum::<f64>() / samples_us.len() as f64;
    println!(
        "validate_client: round-trip latency over {TIMED_CALLS} calls -> min={min:.1}us p50={p50:.1}us mean={mean:.1}us p99={p99:.1}us"
    );

    Ok(())
}
