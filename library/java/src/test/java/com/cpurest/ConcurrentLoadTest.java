package com.cpurest;

import com.cpurest.annotations.CpuClient;
import com.cpurest.annotations.CpuPost;
import com.cpurest.annotations.CpuRestController;
import com.cpurest.server.CpuServer;
import json.JsonCodec;
import json.JsonField;
import json.JsonReader;
import json.JsonWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves cpurest-java's client/server path is correct under genuinely large
 * concurrent caller counts, not a token thread pool. Uses one virtual thread
 * per caller ({@link Executors#newVirtualThreadPerTaskExecutor()}, stable
 * since JDK 21) so "thousands of concurrent callers" costs nothing to spin
 * up — each one parks properly on {@link java.util.concurrent.locks.ReentrantLock}
 * and on the shm wait loop's {@code LockSupport.parkNanos}, both of which
 * unmount a virtual thread from its carrier instead of pinning it, so this
 * doesn't degrade into 10,000 OS threads fighting over a handful of cores.
 *
 * <p>What this does <em>not</em> claim: a bus is a single-slot SPSC mailbox
 * (see {@code docs/PRD.md}'s Known Limitations), so thousands of concurrent
 * callers on the <em>same</em> bus still serialize through one lock one at a
 * time — this proves that serialization holds correctly and fairly under
 * extreme contention, not that thousands of requests execute in parallel on
 * one bus. Parallelism comes from spreading callers across multiple buses
 * (each with its own {@code ServerWorker} daemon thread), which the second
 * test below does.
 */
class ConcurrentLoadTest {

    public record NumRequest(long value) {
        static final JsonField VALUE = JsonField.of("value");
        public static final JsonCodec<NumRequest> CODEC = new JsonCodec<NumRequest>() {
            public void write(JsonWriter w, NumRequest v) {
                w.beginObject();
                w.name(VALUE).value(v.value());
                w.endObject();
            }

            public NumRequest read(JsonReader r) {
                long value = 0;
                r.beginObject();
                while (r.nextKey()) {
                    if (r.keyIs(VALUE)) value = r.readLong();
                    else r.skipValue();
                }
                return new NumRequest(value);
            }
        };
    }

    public record NumResponse(long result) {
        static final JsonField RESULT = JsonField.of("result");
        public static final JsonCodec<NumResponse> CODEC = new JsonCodec<NumResponse>() {
            public void write(JsonWriter w, NumResponse v) {
                w.beginObject();
                w.name(RESULT).value(v.result());
                w.endObject();
            }

            public NumResponse read(JsonReader r) {
                long result = 0;
                r.beginObject();
                while (r.nextKey()) {
                    if (r.keyIs(RESULT)) result = r.readLong();
                    else r.skipValue();
                }
                return new NumResponse(result);
            }
        };
    }

    @CpuRestController(bus = "/cpurest_java_test_concurrent_a")
    public static class MultiplyBy2Controller {
        @CpuPost("/compute")
        public NumResponse compute(NumRequest req) {
            return new NumResponse(req.value() * 2);
        }
    }

    @CpuRestController(bus = "/cpurest_java_test_concurrent_b")
    public static class MultiplyBy3Controller {
        @CpuPost("/compute")
        public NumResponse compute(NumRequest req) {
            return new NumResponse(req.value() * 3);
        }
    }

    @CpuRestController(bus = "/cpurest_java_test_concurrent_c")
    public static class MultiplyBy5Controller {
        @CpuPost("/compute")
        public NumResponse compute(NumRequest req) {
            return new NumResponse(req.value() * 5);
        }
    }

    @CpuRestController(bus = "/cpurest_java_test_concurrent_d")
    public static class MultiplyBy7Controller {
        @CpuPost("/compute")
        public NumResponse compute(NumRequest req) {
            return new NumResponse(req.value() * 7);
        }
    }

    @CpuClient(bus = "/cpurest_java_test_concurrent_a")
    interface MultiplyBy2Client {
        @CpuPost("/compute")
        NumResponse compute(NumRequest req);
    }

    @CpuClient(bus = "/cpurest_java_test_concurrent_b")
    interface MultiplyBy3Client {
        @CpuPost("/compute")
        NumResponse compute(NumRequest req);
    }

    @CpuClient(bus = "/cpurest_java_test_concurrent_c")
    interface MultiplyBy5Client {
        @CpuPost("/compute")
        NumResponse compute(NumRequest req);
    }

    @CpuClient(bus = "/cpurest_java_test_concurrent_d")
    interface MultiplyBy7Client {
        @CpuPost("/compute")
        NumResponse compute(NumRequest req);
    }

    private static CpuServer server;
    private static MultiplyBy2Client client2;
    private static MultiplyBy3Client client3;
    private static MultiplyBy5Client client5;
    private static MultiplyBy7Client client7;

    @BeforeAll
    static void setup() {
        server = CpuRest.server()
                .register(new MultiplyBy2Controller())
                .register(new MultiplyBy3Controller())
                .register(new MultiplyBy5Controller())
                .register(new MultiplyBy7Controller())
                .start();
        client2 = CpuRest.client(MultiplyBy2Client.class);
        client3 = CpuRest.client(MultiplyBy3Client.class);
        client5 = CpuRest.client(MultiplyBy5Client.class);
        client7 = CpuRest.client(MultiplyBy7Client.class);
    }

    @AfterAll
    static void teardown() {
        server.stop();
    }

    /** Thousands of concurrent callers, one bus, one lock: proves the
     * single-slot mailbox's serialization is correct (never crosses two
     * callers' request/response) and fair (nobody starves) under extreme
     * contention — not just the 32-thread token gesture this replaces. */
    @Test
    void thousandsOfConcurrentCallersOnOneBusGetCorrectResponses() throws InterruptedException {
        int callers = 10_000;
        int perCaller = 10;
        long expectedTotal = (long) callers * perCaller;

        AtomicInteger mismatches = new AtomicInteger();
        AtomicLong completed = new AtomicLong();
        List<String> errors = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(callers);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < callers; c++) {
                int callerId = c;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < perCaller; i++) {
                            long value = callerId * 1_000_000L + i;
                            NumResponse resp = client2.compute(new NumRequest(value));
                            completed.incrementAndGet();
                            if (resp.result() != value * 2) {
                                mismatches.incrementAndGet();
                                errors.add("caller " + callerId + " iter " + i + ": expected " + (value * 2) + " got " + resp.result());
                            }
                        }
                    } catch (Throwable e) {
                        mismatches.incrementAndGet();
                        errors.add("caller " + callerId + ": " + e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(2, java.util.concurrent.TimeUnit.MINUTES),
                    "did not complete within 2 minutes: " + completed.get() + "/" + expectedTotal + " calls finished");
        }

        errors.stream().limit(10).forEach(System.out::println);
        assertEquals(expectedTotal, completed.get(), "not every call completed");
        assertEquals(0, mismatches.get(), callers + "x" + perCaller + " concurrent callers on one bus had mismatches");
    }

    /** Four independent buses (four independent ServerWorker daemon threads)
     * each hammered by hundreds of concurrent virtual-thread callers at the
     * same time — this is where actual parallelism (not just contention
     * tolerance) is being exercised: four dispatch threads decoding/encoding
     * concurrently through the same shared Wire.CODECS cache and
     * json-serializer thread-locals, verified never to cross-contaminate
     * (each bus's distinct multiplier makes any mixup immediately visible). */
    @Test
    void fourBusesDispatchedInParallelUnderHeavyLoadDontCrossContaminate() throws InterruptedException {
        int callersPerBus = 500;
        int perCaller = 20;
        int buses = 4;
        long expectedTotal = (long) callersPerBus * perCaller * buses;

        AtomicInteger mismatches = new AtomicInteger();
        AtomicLong completed = new AtomicLong();
        List<String> errors = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(callersPerBus * buses);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            submitLoad(pool, done, completed, mismatches, errors, "x2", callersPerBus, perCaller,
                    v -> client2.compute(new NumRequest(v)).result(), 2);
            submitLoad(pool, done, completed, mismatches, errors, "x3", callersPerBus, perCaller,
                    v -> client3.compute(new NumRequest(v)).result(), 3);
            submitLoad(pool, done, completed, mismatches, errors, "x5", callersPerBus, perCaller,
                    v -> client5.compute(new NumRequest(v)).result(), 5);
            submitLoad(pool, done, completed, mismatches, errors, "x7", callersPerBus, perCaller,
                    v -> client7.compute(new NumRequest(v)).result(), 7);

            assertTrue(done.await(2, java.util.concurrent.TimeUnit.MINUTES),
                    "did not complete within 2 minutes: " + completed.get() + "/" + expectedTotal + " calls finished");
        }

        errors.stream().limit(10).forEach(System.out::println);
        assertEquals(expectedTotal, completed.get(), "not every call completed");
        assertEquals(0, mismatches.get(), "concurrent 4-bus dispatch under load had mismatches/cross-contamination");
    }

    private interface LongUnaryOp {
        long apply(long value) throws Exception;
    }

    private static void submitLoad(
            ExecutorService pool,
            CountDownLatch done,
            AtomicLong completed,
            AtomicInteger mismatches,
            List<String> errors,
            String label,
            int callers,
            int perCaller,
            LongUnaryOp call,
            long multiplier) {
        for (int c = 0; c < callers; c++) {
            int callerId = c;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perCaller; i++) {
                        long value = callerId * 1_000_000L + i;
                        long result = call.apply(value);
                        completed.incrementAndGet();
                        if (result != value * multiplier) {
                            mismatches.incrementAndGet();
                            errors.add(label + " caller " + callerId + " iter " + i + ": expected " + (value * multiplier) + " got " + result);
                        }
                    }
                } catch (Throwable e) {
                    mismatches.incrementAndGet();
                    errors.add(label + " caller " + callerId + ": " + e);
                } finally {
                    done.countDown();
                }
            });
        }
    }
}
