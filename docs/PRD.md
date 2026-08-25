# PRD: cpurest

## Problem statement

Teams that split a workload across Rust and Java on the *same host* (e.g. a Rust data-plane service next to a Java control-plane / business-logic service) default to HTTP over loopback for the boundary because it's what everyone knows how to build and debug: typed routes, JSON bodies, a request/response mental model. That ergonomics choice costs 5–15ms per call in practice (TCP handshake/reuse overhead, kernel socket buffers, JSON-over-sockets serialization) for a call that never left the machine. The alternative — raw FFI — removes that cost but also removes type safety, request/response framing, and fault isolation (a bad call can crash the *host* process). cpurest's goal: keep the HTTP-shaped ergonomics, pay shared-memory-shaped costs.

## Goals

- Typed, declarative routing on both sides (`#[cpu_post]` in Rust; `@CpuPost` in Java) — no manual (de)serialization boilerplate at call sites.
- Round-trip latency in the low tens of microseconds for a same-host call, not milliseconds.
- Fault isolation: a panicking/throwing handler maps to an HTTP-style 500 response, never takes down the host process or wedges the bus for other routes.
- Symmetric roles: either language can be the "server" (bus creator) for a given named bus; the other connects as client. Demonstrated in both directions in `examples/`.
- A server that never blocks the host application: it runs on its own OS thread (Rust) / daemon thread (Java).

## Non-goals (for this version)

- **Not** a general RPC framework across arbitrary language pairs — Rust ⟷ Java only, for now (see Roadmap).
- **Not** a replacement for HTTP when the caller and callee might end up on different hosts (shared memory is host-local by construction; there's no fallback transport).
- **Not** pipelined/concurrent-in-flight requests on one bus — see Known Limitations.
- **Not** authenticated or encrypted — see Known Limitations.

## Users & use cases

The target user is a team running a **modulith-style deployment**: several independently-built services that are nonetheless deployed together on one host (or one pod), where the between-service calls are frequent and latency-sensitive enough that HTTP's overhead shows up in profiles, but the team still wants REST's ergonomics — a typed method call, not raw byte-pushing. Concretely: a Rust-implemented compute-heavy engine (e.g. the example project's tax calculation service) called synchronously and often by a Java-implemented business/orchestration layer, plus occasional callback traffic the other direction (the example's Java-hosted validation route, callable from Rust).

## API surface

### Rust (`cpurest` crate)

- `#[cpu_get(path)]`, `#[cpu_post(path)]`, `#[cpu_put(path)]`, `#[cpu_delete(path)]` — attribute macros on an `async fn` taking zero args or one `Json<T>` arg, returning `Json<R>` or `Result<Json<R>, CpuError>`. Auto-registered into a compile-time route table via `inventory` — no manual route list to maintain.
- `Server::bind(bus_name) -> io::Result<Server>`, `.route_count()`, `.serve() -> ServerHandle`. `ServerHandle::stop()` (also runs on `Drop`).
- `Client::connect(bus_name) -> io::Result<Client>`, `.get/.post/.put/.delete::<Req, Resp>(path, &body).await -> Result<Resp, CpuError>`.
- `CpuError::{bad_request, not_found, internal}(message)`.

### Java (`cpurest-java`)

- `@CpuClient(bus = "...")` on an interface; `@CpuGet/@CpuPost/@CpuPut/@CpuDelete("...")` on its methods. `CpuRest.client(MyClient.class)` returns a `java.lang.reflect.Proxy` implementation.
- `@CpuRestController(bus = "...")` on a class; same method annotations. `CpuRest.server().register(instance).start() -> CpuServer`. `CpuServer.stop()`.
- `CpuException(status, message)` — thrown by the client proxy on a non-2xx response; thrown by a controller method to control the response status explicitly.
- Every request/response DTO must be a **public** type with a `public static final json.JsonCodec<T> CODEC` field — either hand-written, or generated at compile time by annotating a record `@json.JsonRecord` (`com.cpurest.util.Wire` looks for both conventions, in that order, via `Class.getField`/`Class.forName`, and caches the result — see `docs/WHITEPAPER.md` §6). Either way there is no reflective auto-mapping of arbitrary POJOs at runtime; `@JsonRecord`'s code generation happens once, at compile time, and produces the same code you'd write by hand.

## Functional requirements

1. A route registered on one side must be reachable from the other via the declarative API only — no manual header/offset manipulation exposed to application code.
2. Request/response bodies are plain Rust structs (de/serialized via `serde_json`) and public Java records exposing a hand-written `json.JsonCodec<T>` (via `json-serializer`, not Jackson — see `docs/WHITEPAPER.md` §6), with wire field names normalized to `snake_case` regardless of the host language's naming convention (see `docs/WHITEPAPER.md` §3).
3. A handler panic (Rust) or uncaught exception (Java) must produce a `500` response with a JSON `{"error": "..."}` body, not a crash or hang.
4. An unmounted route must produce a `404`, not a hang or a panic.
5. A client connecting before the server has created the bus must retry (bounded by a timeout) rather than fail immediately.

## Non-functional requirements

- **Latency budget**: p50 round trip under 50µs, p99 under 500µs, measured on the reference machine via `scripts/run-demo.sh` (currently met — see `docs/WHITEPAPER.md` §5 for actual measured numbers).
- **Portability**: must run on macOS and Linux without relying on `/dev/shm` as a literal path (macOS doesn't expose one) — always through `shm_open`/`mmap`, never a hardcoded path.
- **Footprint**: no hard dependency on a specific async runtime in the Rust crate (handlers are driven via `futures-executor::block_on` on the server's dedicated thread and a hand-rolled cooperative-yield `Future` on the client side, so the crate is usable inside a host application that runs its own tokio runtime, or none at all).

## Known Limitations

- **Single in-flight request per bus.** Each channel is a single-slot SPSC mailbox, not a multi-slot ring — concurrent calls through the same client (or multiple clients on the same bus) serialize through an internal lock. Sufficient for the synchronous request/response pattern this version targets; see Roadmap for pipelining. That serialization is load-tested, not just assumed: `ConcurrentLoadTest` (`library/java`) drives 10,000 concurrent virtual-thread callers against one bus (100,000 requests through the single-slot mailbox) and, separately, 4 independent buses under 2,000 concurrent callers each (40,000 requests across 4 parallel `ServerWorker` dispatch threads) — zero mismatches, 5 consecutive clean runs. It proves the lock holds correctly and fairly under real contention and that separate buses genuinely dispatch in parallel; it does not claim in-flight parallelism *within* one bus, which the single-slot design doesn't provide by construction.
- **Fixed 1 MiB bus size** (two 512 KiB channels), not configurable per-bus in this version. A payload larger than ~512 KB minus the 192-byte header will be rejected.
- **No transport-level authentication or encryption.** The trust boundary is "same host, same user" (POSIX shm permission bits, `0666`-ish); anything reachable via shared memory on the box can, in principle, open a named bus. Appropriate for a single-tenant host; not appropriate as-is for a multi-tenant one.
- **Two languages today**: Rust and Java only. The wire protocol itself is language-agnostic (a fixed byte layout over shared memory), so a third-language binding is additive, not a redesign.
- **`shm_open` is genuinely variadic (POSIX)**, and an FFM downcall to it must declare `Linker.Option.firstVariadicArg(2)` or the `mode` argument gets corrupted on macOS/arm64 (see `docs/WHITEPAPER.md` §6 and `Libc.java`'s `SHM_OPEN` field for the full story). Fixed in this codebase; noted here because it's the single most likely thing a contributor could accidentally regress if `Libc.java` is touched carelessly.
- **macOS's `shm_open` name limit** (~31 bytes total, `PSHMNAMLEN`) means the actual `shm_open` name is never the bus name verbatim — it's a short slug plus an 8-hex-digit FNV-1a hash of the bus name, computed identically on both sides (`cpurest_core::shm::shm_name` / `HeaderLayout.shmName`). A 32-bit hash has a real (if small) collision probability for a large number of distinct bus names on one host; not an issue at the scale this version targets.
- **`cpurest-java`'s only dependency (`json-serializer`) isn't published anywhere yet.** It lives at `/Users/darshanredkar/darshan/json-serializer` and must be `mvn install`ed locally before building `library/java` (both `scripts/run-demo.sh` and `scripts/run-ui-demo.sh` do this automatically). Publishing it to a real Maven repository is out of scope for this PRD (it's a separate, general-purpose personal library, not cpurest-specific) but is a real prerequisite for anyone else building this project today.
- **Every DTO needs a `JsonCodec`, hand-written or generated — never reflective at runtime.** `@JsonRecord` (see `docs/WHITEPAPER.md` §6) removes the typing burden for the common case (records built from `int`/`long`/`double`/`boolean`/`String`/enum/`List<T>`/nested `@JsonRecord` types) at zero runtime cost, since it's compile-time code generation, not reflection — but it doesn't cover `Map` fields or non-record classes, which still need a hand-written codec. `examples/java/java-backend-demo`'s `TaxRequest`/`TaxResponse` use `@JsonRecord`; `ValidateRequest`/`ValidateResponse` are hand-written, both live side by side.
- **`@JsonRecord` requires an explicit Maven annotation-processor-path declaration.** Maven's default in-process compilation doesn't reliably auto-discover annotation processors from the plain compile classpath (a long-standing Maven quirk affecting every JVM annotation processor, not specific to this one) — any Maven project using `@JsonRecord` needs `<annotationProcessorPaths>` pointing at `com.jsonserializer:json-serializer` in its `maven-compiler-plugin` config (see `examples/java/java-backend-demo/pom.xml` for the working example, or `json-serializer`'s README). Gradle and plain `javac` pick it up with no extra configuration.

## Roadmap (v2+)

- **Pipelined multi-slot ring**: replace the single-slot mailbox with a true lock-free multi-slot ring per channel (sequence numbers, wraparound-safe cursors), enabling multiple in-flight requests per bus without the current internal lock.
- **Configurable bus/payload size** instead of the fixed 1 MiB default.
- **Additional language bindings** (Python via `ctypes`/`cffi`, Go via `cgo`) against the same wire protocol.
- **Optional transport-level auth** (e.g. a shared secret exchanged out-of-band, checked per-request) for multi-tenant hosts.
- **Streaming/chunked bodies** for payloads that exceed one channel's capacity.
