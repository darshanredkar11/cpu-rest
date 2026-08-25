package com.cpurest;

import com.cpurest.client.CpuClientProxyFactory;
import com.cpurest.server.CpuServer;

/**
 * Entry point for cpurest-java.
 *
 * <pre>{@code
 * @CpuClient(bus = "/tax_engine")
 * interface TaxClient {
 *     @CpuPost("/calculate")
 *     TaxResponse calculate(TaxRequest req);
 * }
 *
 * TaxClient client = CpuRest.client(TaxClient.class);
 * TaxResponse resp = client.calculate(new TaxRequest(85_000, 12_950));
 *
 * @CpuRestController(bus = "/java_backend")
 * class ValidationController {
 *     @CpuPost("/validate")
 *     public ValidateResponse validate(ValidateRequest req) { ... }
 * }
 *
 * CpuServer server = CpuRest.server().register(new ValidationController()).start();
 * }</pre>
 */
public final class CpuRest {
    private CpuRest() {}

    /** Build a proxy implementing a {@code @CpuClient}-annotated interface. */
    public static <T> T client(Class<T> clientInterface) {
        return CpuClientProxyFactory.create(clientInterface);
    }

    /** Build a server; call {@link CpuServer#register} then {@link CpuServer#start}. */
    public static CpuServer server() {
        return new CpuServer();
    }
}
