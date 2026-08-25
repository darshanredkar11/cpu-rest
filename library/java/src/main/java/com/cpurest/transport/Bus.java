package com.cpurest.transport;

import java.time.Duration;

import static com.cpurest.transport.HeaderLayout.BUS_SIZE;
import static com.cpurest.transport.HeaderLayout.CHANNEL_SIZE;

/**
 * A named 1 MiB shared-memory segment split into two {@link RingChannel}s.
 * Whichever side calls {@link #createServer} creates (and owns/cleans up)
 * the segment; channel roles are fixed relative to that creator, not to
 * which language is on which side:
 *
 * <ul>
 *   <li>Request channel (offset 0): written by the client, read by the server.</li>
 *   <li>Response channel (offset {@code CHANNEL_SIZE}): written by the server, read by the client.</li>
 * </ul>
 *
 * This is what lets the same {@code Bus} type work whether Rust or Java is
 * the server on a given bus name.
 */
public final class Bus implements AutoCloseable {
    private final ShmRegion region;
    private final RingChannel request;
    private final RingChannel response;

    private Bus(ShmRegion region) {
        this.region = region;
        this.request = new RingChannel(region.segment().asSlice(0, CHANNEL_SIZE));
        this.response = new RingChannel(region.segment().asSlice(CHANNEL_SIZE, CHANNEL_SIZE));
    }

    /** Create and own the named bus. This side is the server for it. */
    public static Bus createServer(String name) {
        return new Bus(ShmRegion.create(name, BUS_SIZE));
    }

    /** Connect to a bus created by the peer. This side is the client. */
    public static Bus connectClient(String name, Duration timeout) {
        return new Bus(ShmRegion.openExisting(name, BUS_SIZE, timeout));
    }

    public RingChannel requestChannel() {
        return request;
    }

    public RingChannel responseChannel() {
        return response;
    }

    @Override
    public void close() {
        region.close();
    }
}
