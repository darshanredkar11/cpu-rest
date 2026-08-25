package com.cpurest.client;

import com.cpurest.transport.Bus;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One {@link Bus} connection (and one call lock) per bus name, shared by
 * every {@code @CpuClient} proxy targeting it — so two interfaces pointed
 * at the same bus don't each map the shared memory segment separately, and
 * concurrent calls through either of them still serialize correctly on the
 * bus's single-slot mailbox.
 */
final class ClientBusRegistry {
    private ClientBusRegistry() {}

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final ConcurrentHashMap<String, Bus> BUSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    static Bus get(String busName) {
        return BUSES.computeIfAbsent(busName, name -> Bus.connectClient(name, CONNECT_TIMEOUT));
    }

    static ReentrantLock lockFor(String busName) {
        return LOCKS.computeIfAbsent(busName, name -> new ReentrantLock());
    }
}
