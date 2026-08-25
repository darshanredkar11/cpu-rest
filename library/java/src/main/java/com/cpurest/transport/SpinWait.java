package com.cpurest.transport;

import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.LockSupport;

/**
 * Hybrid spin/yield/park wait on the header's atomic state flag. Mirrors
 * {@code cpurest-core::sync}. The writer claims a slot transition with a
 * real CAS; the reader spins on plain volatile reads: a tight busy-spin for
 * the first ~500ns (the common case), then {@link Thread#onSpinWait()}
 * cooperative yielding, then short parked sleeps if the peer is slow, so an
 * idle bus doesn't peg a core at 100%.
 */
final class SpinWait {
    private SpinWait() {}

    /** Roughly 500ns worth of spin iterations on typical modern hardware. */
    private static final int TIGHT_SPIN_ITERS = 2000;
    private static final int YIELD_ITERS = 200;
    private static final long PARK_NANOS = 50_000; // 50 microseconds

    static boolean tryClaim(VarHandle flagHandle, java.lang.foreign.MemorySegment segment, long offset, int from, int to) {
        return flagHandle.compareAndSet(segment, offset, from, to);
    }

    static void publish(VarHandle flagHandle, java.lang.foreign.MemorySegment segment, long offset, int state) {
        flagHandle.setVolatile(segment, offset, state);
    }

    /** Bounded busy-spin only, safe to call frequently from a hot poll loop. */
    static boolean pollSpin(VarHandle flagHandle, java.lang.foreign.MemorySegment segment, long offset, int want, int spins) {
        for (int i = 0; i < spins; i++) {
            if ((int) flagHandle.getVolatile(segment, offset) == want) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    static void blockingWait(VarHandle flagHandle, java.lang.foreign.MemorySegment segment, long offset, int want, Duration timeout)
            throws TimeoutException {
        Instant deadline = Instant.now().plus(timeout);

        if (pollSpin(flagHandle, segment, offset, want, TIGHT_SPIN_ITERS)) {
            return;
        }
        for (int i = 0; i < YIELD_ITERS; i++) {
            if ((int) flagHandle.getVolatile(segment, offset) == want) {
                return;
            }
            Thread.yield();
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException();
            }
        }
        while (true) {
            if ((int) flagHandle.getVolatile(segment, offset) == want) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException();
            }
            LockSupport.parkNanos(PARK_NANOS);
        }
    }

    static final class TimeoutException extends Exception {
        TimeoutException() {
            super("timed out waiting on cpurest shared-memory bus");
        }
    }
}
