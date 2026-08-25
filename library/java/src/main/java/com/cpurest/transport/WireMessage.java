package com.cpurest.transport;

/** A decoded message read off a {@link RingChannel}. Mirrors Rust's {@code WireMessage}. */
public record WireMessage(int method, int status, String route, byte[] payload) {}
