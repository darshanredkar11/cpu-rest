# cpurest

**HTTP/REST ergonomics at shared-memory speed.** A polyglot IPC framework connecting co-located Rust and Java processes over POSIX shared memory — typed routes, JSON DTOs, a request/response mental model everyone already knows, at ~10–50µs round trips instead of the 5–15ms a loopback HTTP call typically costs.

```rust
#[cpu_post("/calculate")]
async fn calculate(Json(req): Json<TaxRequest>) -> Result<Json<TaxResponse>, CpuError> {
    Ok(Json(TaxResponse { tax_owed: req.income * 0.2, effective_rate: 0.2 }))
}
let handle = Server::bind("/tax_engine")?.serve();
```

```java
@CpuClient(bus = "/tax_engine")
interface TaxClient {
    @CpuPost("/calculate")
    TaxResponse calculate(TaxRequest request);
}
TaxClient client = CpuRest.client(TaxClient.class);
TaxResponse resp = client.calculate(new TaxRequest(85_000, 12_950));
```

Read the full pitch and wire-protocol spec in [`docs/WHITEPAPER.md`](docs/WHITEPAPER.md); goals, API surface, and known limitations in [`docs/PRD.md`](docs/PRD.md).

## Quick start

```bash
./scripts/run-demo.sh
```

Builds the library, then the examples, starts the Rust `tax-engine-rust` example server, runs the Java `java-backend-demo` example (which calls into Rust *and* hosts a route Rust calls back into), and prints the measured round-trip latency on your machine.

For an interactive session — a live console in your browser to fire arbitrary requests at either side and watch latency in real time — run `./scripts/run-ui-demo.sh` instead and open `http://127.0.0.1:8089`.

Requires: Rust/Cargo, Maven, and a JDK 22+ on `PATH` (the Java side uses the `java.lang.foreign` Foreign Function & Memory API, stable since JDK 22).

## Layout

`library/` is what you'd actually depend on from another project — self-contained per language, nothing example-shaped inside it. `examples/` are separate consumer projects that depend on `library/`, never the reverse.

```
cpu-rest/
├── docs/
│   ├── WHITEPAPER.md   # architecture, wire protocol, measured latency
│   └── PRD.md          # goals, API surface, known limitations, roadmap
├── library/
│   ├── rust/            # the cpurest crates — publishable, no example code inside
│   │   ├── cpurest-core/  # shared-memory transport: shm, ring channel, header layout, sync
│   │   ├── cpurest-macros/ # #[cpu_get]/#[cpu_post]/#[cpu_put]/#[cpu_delete] attribute macros
│   │   └── cpurest/       # public API: Server, Client, Json<T>, CpuError
│   └── java/             # cpurest-java — annotations, FFM transport, client proxy, server dispatcher
├── examples/
│   ├── rust/
│   │   ├── tax-engine-rust/  # example Rust server (+ a Rust-as-client binary)
│   │   └── demo-ui/          # local HTTP bridge + browser console for the interactive demo
│   └── java/
│       └── java-backend-demo/ # example Java app: client into Rust + a Rust-callable controller
└── scripts/
    ├── run-demo.sh      # scripted round trip + latency benchmark
    └── run-ui-demo.sh   # interactive browser console
```

Each example tree is its own build (its own `Cargo.toml`/`pom.xml`), depending on `library/` by path (Rust) or by installing it to the local Maven repository first (Java) — not a shared reactor with the library.

## Why this exists

Same-host process splits (a Rust engine next to a Java orchestration layer, say) default to HTTP over loopback because it's the ergonomics everyone knows — typed routes, JSON, request/response — even though the call never leaves the machine. That costs milliseconds for a call that could cost microseconds. The usual fast alternative, raw FFI, buys back the latency but gives up type safety, request framing, and fault isolation (a bad call can crash the *host* process). cpurest keeps the HTTP-shaped ergonomics and pays shared-memory-shaped costs instead — see `docs/WHITEPAPER.md` for the full argument and the measured numbers.

## License

MIT OR Apache-2.0 (see `library/rust/Cargo.toml`).
