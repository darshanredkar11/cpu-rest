package com.cpurest.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a declarative cpurest client. {@link com.cpurest.CpuRest#client}
 * builds a {@link java.lang.reflect.Proxy} implementing it, dispatching each
 * {@code @CpuGet}/{@code @CpuPost}/{@code @CpuPut}/{@code @CpuDelete} method
 * over the named shared-memory bus.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CpuClient {
    /** Bus name, e.g. {@code "/tax_engine"}. Must match the server's {@code bus()}. */
    String bus();
}
