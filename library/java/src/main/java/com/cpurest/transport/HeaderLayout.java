package com.cpurest.transport;

import java.nio.charset.StandardCharsets;

/**
 * Wire layout constants for one direction of a cpurest bus. Must stay
 * byte-for-byte identical to {@code cpurest-core/src/header.rs} and
 * {@code shm.rs} on the Rust side — this is the entire cross-language
 * contract.
 *
 * <p>3 cache lines (192B), not a literal 64B struct: the state flag is the
 * only field either side polls, so it gets an entire 64-byte line to itself
 * to avoid false-sharing with the cold method/status/route/length fields.
 */
public final class HeaderLayout {
    private HeaderLayout() {}

    public static final int CACHE_LINE = 64;

    public static final int FLAG_OFFSET = 0;
    public static final int METHOD_OFFSET = CACHE_LINE;
    public static final int STATUS_OFFSET = METHOD_OFFSET + 2;
    public static final int LEN_OFFSET = METHOD_OFFSET + 4;
    public static final int ROUTE_OFFSET = CACHE_LINE * 2;
    public static final int ROUTE_BUF_LEN = 64;
    public static final int HEADER_SIZE = CACHE_LINE * 3;

    public static final int CHANNEL_SIZE = 512 * 1024;
    public static final long PAYLOAD_CAPACITY = CHANNEL_SIZE - HEADER_SIZE;
    public static final long BUS_SIZE = (long) CHANNEL_SIZE * 2;

    public static final int STATE_IDLE = 0;
    public static final int STATE_REQ_READY = 1;
    public static final int STATE_RESP_READY = 2;

    // Must match cpurest_core::header::Method's discriminants exactly.
    public static final int METHOD_GET = 0;
    public static final int METHOD_POST = 1;
    public static final int METHOD_PUT = 2;
    public static final int METHOD_DELETE = 3;

    /**
     * FNV-1a 32-bit over the UTF-8 bytes of the trimmed bus name. Must match
     * {@code cpurest_core::shm::fnv1a32} exactly, byte for byte.
     */
    static int fnv1a32(byte[] bytes) {
        int hash = 0x811c9dc5;
        for (byte b : bytes) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        return hash;
    }

    /**
     * The actual POSIX {@code shm_open} name for a bus name — never the bus
     * name itself. macOS's {@code shm_open} rejects names over ~31 bytes
     * total ({@code PSHMNAMLEN}), so this keeps a short human-readable slug
     * (first 10 alphanumeric characters) plus an 8-hex-digit FNV-1a hash of
     * the full trimmed name for uniqueness. Must match
     * {@code cpurest_core::shm::shm_name} exactly, since a Rust server and a
     * Java client (or vice versa) have to derive the same name to rendezvous.
     */
    public static String shmName(String bus) {
        String trimmed = bus.startsWith("/") ? bus.substring(1) : bus;
        if (trimmed.isEmpty() || trimmed.contains("/")) {
            throw new IllegalArgumentException("invalid bus name: " + bus);
        }
        StringBuilder slug = new StringBuilder();
        for (int i = 0; i < trimmed.length() && slug.length() < 10; i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 128) {
                slug.append(c);
            }
        }
        int hash = fnv1a32(trimmed.getBytes(StandardCharsets.UTF_8));
        return String.format("/cr_%s_%08x", slug, hash);
    }
}
