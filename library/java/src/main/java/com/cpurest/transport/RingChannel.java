package com.cpurest.transport;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.cpurest.transport.HeaderLayout.*;

/**
 * One direction of a bus: a {@code CHANNEL_SIZE}-byte slice starting with
 * the header described in {@link HeaderLayout}, followed by the payload
 * buffer. Despite the name (kept for parity with the Rust side and the
 * spec's vocabulary), this is a single-slot SPSC mailbox, not a multi-slot
 * ring — see the note in {@code cpurest-core::ring} for why.
 */
public final class RingChannel {
    private static final VarHandle FLAG = ValueLayout.JAVA_INT.varHandle();
    private static final byte[] ZERO_ROUTE_BUF = new byte[ROUTE_BUF_LEN];

    private final MemorySegment base;

    RingChannel(MemorySegment base) {
        this.base = base;
    }

    /** Block (hybrid spin/yield/park) until the flag reads IDLE, write the message, then CAS IDLE -&gt; readyState. */
    public void write(int readyState, int method, int status, String route, byte[] payload, Duration timeout) {
        if (payload.length > PAYLOAD_CAPACITY) {
            throw new IllegalArgumentException(
                    "payload of " + payload.length + " bytes exceeds channel capacity of " + PAYLOAD_CAPACITY + " bytes");
        }
        byte[] routeBytes = route.getBytes(StandardCharsets.UTF_8);
        if (routeBytes.length >= ROUTE_BUF_LEN) {
            throw new IllegalArgumentException("route \"" + route + "\" exceeds " + ROUTE_BUF_LEN + "-byte buffer");
        }

        try {
            SpinWait.blockingWait(FLAG, base, FLAG_OFFSET, STATE_IDLE, timeout);
        } catch (SpinWait.TimeoutException e) {
            throw new TransportTimeoutException(e.getMessage());
        }

        base.set(ValueLayout.JAVA_BYTE, METHOD_OFFSET, (byte) method);
        base.set(ValueLayout.JAVA_SHORT, STATUS_OFFSET, (short) status);
        base.set(ValueLayout.JAVA_INT, LEN_OFFSET, payload.length);

        MemorySegment.copy(ZERO_ROUTE_BUF, 0, base, ValueLayout.JAVA_BYTE, ROUTE_OFFSET, ROUTE_BUF_LEN);
        MemorySegment.copy(routeBytes, 0, base, ValueLayout.JAVA_BYTE, ROUTE_OFFSET, routeBytes.length);

        MemorySegment.copy(payload, 0, base, ValueLayout.JAVA_BYTE, HEADER_SIZE, payload.length);

        if (!SpinWait.tryClaim(FLAG, base, FLAG_OFFSET, STATE_IDLE, readyState)) {
            throw new IllegalStateException("lost the race to publish onto this channel (concurrent writer?)");
        }
    }

    /** Block until the flag reads {@code readyState}, then copy out the message. Does not reset the flag. */
    public WireMessage waitAndRead(int readyState, Duration timeout) {
        try {
            SpinWait.blockingWait(FLAG, base, FLAG_OFFSET, readyState, timeout);
        } catch (SpinWait.TimeoutException e) {
            throw new TransportTimeoutException(e.getMessage());
        }
        return readUnchecked();
    }

    /** Non-blocking: bounded spin only, returns {@code null} if not ready yet. */
    public WireMessage tryRead(int readyState, int spins) {
        if (SpinWait.pollSpin(FLAG, base, FLAG_OFFSET, readyState, spins)) {
            return readUnchecked();
        }
        return null;
    }

    private WireMessage readUnchecked() {
        int method = base.get(ValueLayout.JAVA_BYTE, METHOD_OFFSET);
        int status = base.get(ValueLayout.JAVA_SHORT, STATUS_OFFSET);
        int len = base.get(ValueLayout.JAVA_INT, LEN_OFFSET);
        len = Math.max(0, Math.min(len, (int) PAYLOAD_CAPACITY));

        byte[] routeBuf = new byte[ROUTE_BUF_LEN];
        MemorySegment.copy(base, ValueLayout.JAVA_BYTE, ROUTE_OFFSET, routeBuf, 0, ROUTE_BUF_LEN);
        int nul = 0;
        while (nul < routeBuf.length && routeBuf[nul] != 0) {
            nul++;
        }
        String route = new String(routeBuf, 0, nul, StandardCharsets.UTF_8);

        byte[] payload = new byte[len];
        MemorySegment.copy(base, ValueLayout.JAVA_BYTE, HEADER_SIZE, payload, 0, len);

        return new WireMessage(method, status, route, payload);
    }

    /** Reset the flag to IDLE, freeing the slot for the next write. */
    public void consume() {
        SpinWait.publish(FLAG, base, FLAG_OFFSET, STATE_IDLE);
    }

    public static final class TransportTimeoutException extends RuntimeException {
        public TransportTimeoutException(String message) {
            super(message);
        }
    }
}
