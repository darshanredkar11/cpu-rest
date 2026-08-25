package com.cpurest;

/**
 * An HTTP-style error crossing the bus: a status code plus a message,
 * JSON-encoded as {@code {"error": message}} on the wire. Thrown by a
 * {@code @CpuClient} proxy when the peer responds with a non-2xx status,
 * and by {@code @CpuRestController} methods to control the response status
 * explicitly (uncaught exceptions of any other type are mapped to 500 by
 * the server, matching the Rust side's panic-to-500 translation).
 */
public class CpuException extends RuntimeException {
    private final int status;
    private final String message;

    public CpuException(int status, String message) {
        super(status + " " + message);
        this.status = status;
        this.message = message;
    }

    public int status() {
        return status;
    }

    /** The raw message, without the status-code prefix {@link #getMessage()} adds — this is what goes on the wire. */
    public String message() {
        return message;
    }

    public static CpuException badRequest(String message) {
        return new CpuException(400, message);
    }

    public static CpuException notFound(String message) {
        return new CpuException(404, message);
    }

    public static CpuException internal(String message) {
        return new CpuException(500, message);
    }
}
