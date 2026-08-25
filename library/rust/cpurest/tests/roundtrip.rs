//! End-to-end proof that the whole stack (macro-registered route -> shm
//! bus -> typed client) actually round-trips, entirely within one process
//! (both `Server` and `Client` map the same POSIX shm segment).

use cpurest::{cpu_post, CpuError, Json, Server};
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, PartialEq)]
struct EchoRequest {
    value: i64,
}

#[derive(Debug, Serialize, Deserialize, PartialEq)]
struct EchoResponse {
    doubled: i64,
}

#[cpu_post("/echo")]
async fn echo(Json(req): Json<EchoRequest>) -> Json<EchoResponse> {
    Json(EchoResponse { doubled: req.value * 2 })
}

#[cpu_post("/boom")]
async fn boom(Json(_req): Json<EchoRequest>) -> Result<Json<EchoResponse>, CpuError> {
    if true {
        panic!("intentional handler panic for the test");
    }
    Ok(Json(EchoResponse { doubled: 0 }))
}

#[cpu_post("/reject")]
async fn reject(Json(_req): Json<EchoRequest>) -> Result<Json<EchoResponse>, CpuError> {
    Err(CpuError::bad_request("nope"))
}

fn bus_name() -> String {
    format!("/cpurest_test_roundtrip_{}", std::process::id())
}

#[test]
fn round_trips_a_typed_request() {
    let bus = bus_name();
    let server = Server::bind(&bus).expect("bind server");
    assert!(server.route_count() >= 3, "expected macro-registered routes to be discovered");
    let handle = server.serve();

    let client = cpurest::Client::connect(&bus).expect("connect client");
    let resp: EchoResponse =
        futures_executor::block_on(client.post("/echo", &EchoRequest { value: 21 })).expect("echo call");
    assert_eq!(resp, EchoResponse { doubled: 42 });

    handle.stop();
}

#[test]
fn panicking_handler_maps_to_500() {
    let bus = format!("{}_panic", bus_name());
    let server = Server::bind(&bus).expect("bind server");
    let handle = server.serve();

    let client = cpurest::Client::connect(&bus).expect("connect client");
    let err = futures_executor::block_on(client.post::<_, EchoResponse>("/boom", &EchoRequest { value: 1 }))
        .expect_err("handler panic should surface as an error");
    assert_eq!(err.status, 500);

    handle.stop();
}

#[test]
fn cpu_error_status_propagates_to_client() {
    let bus = format!("{}_reject", bus_name());
    let server = Server::bind(&bus).expect("bind server");
    let handle = server.serve();

    let client = cpurest::Client::connect(&bus).expect("connect client");
    let err = futures_executor::block_on(client.post::<_, EchoResponse>("/reject", &EchoRequest { value: 1 }))
        .expect_err("CpuError should propagate");
    assert_eq!(err.status, 400);
    assert_eq!(err.message, "nope");

    handle.stop();
}

#[test]
fn unknown_route_returns_404() {
    let bus = format!("{}_404", bus_name());
    let server = Server::bind(&bus).expect("bind server");
    let handle = server.serve();

    let client = cpurest::Client::connect(&bus).expect("connect client");
    let err = futures_executor::block_on(client.post::<_, EchoResponse>("/nonexistent", &EchoRequest { value: 1 }))
        .expect_err("unmounted route should 404");
    assert_eq!(err.status, 404);

    handle.stop();
}
