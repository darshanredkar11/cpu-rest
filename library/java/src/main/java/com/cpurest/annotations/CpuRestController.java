package com.cpurest.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a cpurest server controller. {@link com.cpurest.server.CpuServer#register}
 * scans it for {@code @CpuGet}/{@code @CpuPost}/{@code @CpuPut}/{@code @CpuDelete}
 * methods and mounts them on the named bus, which this side creates and owns.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CpuRestController {
    /** Bus name, e.g. {@code "/java_backend"}. This process becomes the server for it. */
    String bus();
}
