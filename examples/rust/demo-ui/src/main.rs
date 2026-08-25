//! Local HTTP bridge for the interactive cpurest demo UI.
//!
//! A browser can't speak the shared-memory wire protocol directly, so this
//! binary sits in between: it serves a single static page and one JSON API
//! endpoint that forwards an arbitrary `{bus, method, path, payload}` call
//! through a real `cpurest::Client`, round-trips it over shared memory
//! exactly like `validate_client` or `java-backend-demo` do, and reports
//! back the response plus the measured latency. It never creates a bus
//! itself — `tax-engine-rust` and `java -jar java-backend-demo.jar --serve`
//! must already be running as the two servers.

use cpurest::{Client, CpuError};
use serde_json::Value;
use std::collections::HashMap;
use std::io::Cursor;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tiny_http::{Header, Method as HttpMethod, Response, Server};

const ADDR: &str = "127.0.0.1:8089";
const INDEX_HTML: &str = include_str!("../assets/index.html");
const CALL_TIMEOUT: Duration = Duration::from_secs(2);
const STATUS_TIMEOUT: Duration = Duration::from_millis(300);

fn main() {
    let server = Server::http(ADDR).unwrap_or_else(|e| panic!("failed to bind {ADDR}: {e}"));
    println!("demo-ui: listening on http://{ADDR}");
    println!("demo-ui: open that URL in a browser.");
    println!("demo-ui: make sure both servers are already running (or just use scripts/run-ui-demo.sh):");
    println!("demo-ui:   examples/rust/target/release/tax-engine-rust");
    println!("demo-ui:   java -jar examples/java/java-backend-demo/target/java-backend-demo.jar --serve");

    // Pre-warm both connections at startup rather than lazily on the first
    // browser click: connecting a Client involves an mmap handshake that's
    // a one-time few-hundred-microsecond-to-millisecond cost, and paying it
    // here means the first number a user sees in the UI reflects steady-
    // state round-trip latency, not connection setup.
    for bus in ["/tax_engine", "/java_backend"] {
        match get_or_connect(bus, CALL_TIMEOUT) {
            Ok(_) => println!("demo-ui: pre-warmed connection to {bus}"),
            Err(e) => println!("demo-ui: could not pre-warm {bus} yet ({e}) — will retry lazily on first call"),
        }
    }

    for request in server.incoming_requests() {
        handle(request);
    }
}

fn handle(mut request: tiny_http::Request) {
    let method = request.method().clone();
    let url = request.url().to_string();

    let response = match (method, url.as_str()) {
        (HttpMethod::Get, "/") => html_response(INDEX_HTML),
        (HttpMethod::Get, "/api/status") => json_response(200, &handle_status()),
        (HttpMethod::Post, "/api/call") => {
            let mut body = String::new();
            match request.as_reader().read_to_string(&mut body) {
                Ok(_) => json_response(200, &handle_call(&body)),
                Err(e) => json_response(400, &error_envelope(&format!("failed to read request body: {e}"))),
            }
        }
        _ => json_response(404, &error_envelope("not found")),
    };
    let _ = request.respond(response);
}

fn handle_call(body: &str) -> Value {
    let parsed: Value = match serde_json::from_str(body) {
        Ok(v) => v,
        Err(e) => return error_envelope(&format!("invalid JSON request: {e}")),
    };

    let bus = match parsed.get("bus").and_then(Value::as_str) {
        Some(b) => b.to_string(),
        None => return error_envelope("missing \"bus\" field"),
    };
    let method = parsed
        .get("method")
        .and_then(Value::as_str)
        .unwrap_or("POST")
        .to_uppercase();
    let path = match parsed.get("path").and_then(Value::as_str) {
        Some(p) => p.to_string(),
        None => return error_envelope("missing \"path\" field"),
    };
    let payload = parsed.get("payload").cloned().unwrap_or(Value::Null);

    let client = match get_or_connect(&bus, CALL_TIMEOUT) {
        Ok(c) => c,
        Err(e) => return error_envelope(&e),
    };

    let start = Instant::now();
    let result = dispatch(&client, &method, &path, payload);
    let latency_us = start.elapsed().as_secs_f64() * 1_000_000.0;

    match result {
        Ok(value) => serde_json::json!({
            "ok": true, "status": 200, "latency_us": latency_us,
            "bus": bus, "method": method, "path": path, "body": value,
        }),
        Err(err) => serde_json::json!({
            "ok": false, "status": err.status, "latency_us": latency_us,
            "bus": bus, "method": method, "path": path, "error": err.message,
        }),
    }
}

fn dispatch(client: &Client, method: &str, path: &str, payload: Value) -> Result<Value, CpuError> {
    futures_executor::block_on(async {
        match method {
            "GET" => client.get::<Value>(path).await,
            "POST" => client.post::<Value, Value>(path, &payload).await,
            "PUT" => client.put::<Value, Value>(path, &payload).await,
            "DELETE" => client.delete::<Value>(path).await,
            other => Err(CpuError::bad_request(format!("unsupported method {other:?} (use GET/POST/PUT/DELETE)"))),
        }
    })
}

fn handle_status() -> Value {
    let mut buses = serde_json::Map::new();
    for bus in ["/tax_engine", "/java_backend"] {
        let up = get_or_connect(bus, STATUS_TIMEOUT).is_ok();
        buses.insert(bus.to_string(), Value::Bool(up));
    }
    Value::Object(buses)
}

fn get_or_connect(bus: &str, timeout: Duration) -> Result<Arc<Client>, String> {
    static CLIENTS: OnceLock<Mutex<HashMap<String, Arc<Client>>>> = OnceLock::new();
    let clients = CLIENTS.get_or_init(|| Mutex::new(HashMap::new()));

    let mut guard = clients.lock().unwrap();
    if let Some(existing) = guard.get(bus) {
        return Ok(existing.clone());
    }
    let client = Client::connect_timeout(bus, timeout)
        .map_err(|e| format!("failed to connect to bus {bus:?}: {e} (is its server running?)"))?;
    let client = Arc::new(client);
    guard.insert(bus.to_string(), client.clone());
    Ok(client)
}

fn error_envelope(message: &str) -> Value {
    serde_json::json!({ "ok": false, "status": 0, "latency_us": 0.0, "error": message })
}

fn html_response(body: &str) -> Response<Cursor<Vec<u8>>> {
    Response::from_string(body)
        .with_header(Header::from_bytes(&b"Content-Type"[..], &b"text/html; charset=utf-8"[..]).unwrap())
}

fn json_response(status: u16, value: &Value) -> Response<Cursor<Vec<u8>>> {
    Response::from_string(value.to_string())
        .with_status_code(status)
        .with_header(Header::from_bytes(&b"Content-Type"[..], &b"application/json"[..]).unwrap())
}
