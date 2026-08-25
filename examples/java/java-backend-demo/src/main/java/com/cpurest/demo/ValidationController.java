package com.cpurest.demo;

import com.cpurest.annotations.CpuPost;
import com.cpurest.annotations.CpuRestController;

/**
 * The other direction of the demo: Java as the *server*. Rust's
 * {@code validate_client} example binary calls this over shared memory,
 * proving the framework isn't one-directional (Rust-serves-Java only).
 */
@CpuRestController(bus = "/java_backend")
public class ValidationController {
    @CpuPost("/validate")
    public ValidateResponse validate(ValidateRequest request) {
        if (request.income() < 0) {
            return new ValidateResponse(false, "income must be non-negative");
        }
        return new ValidateResponse(true, null);
    }
}
