package com.cpurest.demo;

import com.cpurest.CpuRest;
import com.cpurest.server.CpuServer;

import java.util.Arrays;

/**
 * Runs both directions of the polyglot demo in one process:
 * <ol>
 *   <li>Java as <b>client</b>: calls {@code POST /calculate} on Rust's
 *       {@code tax-engine-rust} example server over bus {@code /tax_engine},
 *       then benchmarks round-trip latency.</li>
 *   <li>Java as <b>server</b>: hosts {@code POST /validate} on bus
 *       {@code /java_backend} for a few seconds so Rust's
 *       {@code validate_client} example binary can call back into it.</li>
 * </ol>
 * Run directly with {@code java -jar java-backend-demo.jar} — not
 * {@code mvn exec:java} (see {@code docs/PRD.md}'s Known Limitations for why).
 *
 * <p>Pass {@code --serve} to skip the one-shot benchmark and instead host
 * {@code /java_backend} indefinitely (until Ctrl+C / SIGTERM) — this is the
 * mode the interactive {@code demo-ui} bridge expects, since it needs the
 * bus to stay up for the whole browser session rather than for a fixed 5s
 * window.
 */
public final class Main {
    private static final int WARMUP_CALLS = 200;
    private static final int TIMED_CALLS = 2000;
    private static final long LISTEN_WINDOW_MILLIS = 5000;

    public static void main(String[] args) throws Exception {
        boolean serveForever = args.length > 0 && args[0].equals("--serve");

        System.out.println("java-backend-demo: starting @CpuRestController on bus \"/java_backend\"...");
        CpuServer server = CpuRest.server().register(new ValidationController()).start();

        if (serveForever) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            System.out.println("java-backend-demo: serving /java_backend/validate indefinitely. Ctrl+C to stop.");
            new java.util.concurrent.CountDownLatch(1).await(); // blocks forever; shutdown hook handles cleanup
            return;
        }

        System.out.println("java-backend-demo: connecting @CpuClient to bus \"/tax_engine\" (is tax-engine-rust running?)...");
        TaxClient taxClient = CpuRest.client(TaxClient.class);

        TaxRequest sample = new TaxRequest(85_000, 12_950);
        TaxResponse sampleResponse = taxClient.calculate(sample);
        System.out.printf(
                "java -> rust: POST /tax_engine/calculate %s => taxOwed=%.2f effectiveRate=%.4f%n",
                sample, sampleResponse.taxOwed(), sampleResponse.effectiveRate());

        System.out.printf(
                "java-backend-demo: benchmarking round-trip latency (%d warmup + %d timed calls)...%n",
                WARMUP_CALLS, TIMED_CALLS);
        for (int i = 0; i < WARMUP_CALLS; i++) {
            taxClient.calculate(sample);
        }
        long[] samplesNanos = new long[TIMED_CALLS];
        for (int i = 0; i < TIMED_CALLS; i++) {
            long start = System.nanoTime();
            taxClient.calculate(sample);
            samplesNanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(samplesNanos);
        printLatencyStats(samplesNanos);

        System.out.printf(
                "java-backend-demo: /java_backend/validate is live; waiting up to %dms for a Rust caller (validate_client)...%n",
                LISTEN_WINDOW_MILLIS);
        Thread.sleep(LISTEN_WINDOW_MILLIS);

        server.stop();
        System.out.println("java-backend-demo: stopped.");
    }

    private static void printLatencyStats(long[] sortedNanos) {
        double minUs = sortedNanos[0] / 1000.0;
        double p50Us = sortedNanos[sortedNanos.length / 2] / 1000.0;
        double p99Us = sortedNanos[(int) (sortedNanos.length * 0.99)] / 1000.0;
        double meanUs = Arrays.stream(sortedNanos).average().orElse(0) / 1000.0;
        System.out.printf(
                "java-backend-demo: round-trip latency over %d calls -> min=%.1fus p50=%.1fus mean=%.1fus p99=%.1fus%n",
                sortedNanos.length, minUs, p50Us, meanUs, p99Us);
    }

    private Main() {}
}
