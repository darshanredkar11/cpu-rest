package com.cpurest.demo;

import com.cpurest.annotations.CpuClient;
import com.cpurest.annotations.CpuPost;

/** Declarative client for the Rust tax-engine-rust example server. */
@CpuClient(bus = "/tax_engine")
public interface TaxClient {
    @CpuPost("/calculate")
    TaxResponse calculate(TaxRequest request);
}
