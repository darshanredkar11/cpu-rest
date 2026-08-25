package com.cpurest.util;

import com.cpurest.annotations.CpuDelete;
import com.cpurest.annotations.CpuGet;
import com.cpurest.annotations.CpuPost;
import com.cpurest.annotations.CpuPut;
import com.cpurest.transport.HeaderLayout;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Extracts the (wire method, path) pair from a {@code @CpuGet}/{@code @CpuPost}/
 * {@code @CpuPut}/{@code @CpuDelete}-annotated method. Shared by the client
 * proxy (reading a {@code @CpuClient} interface's methods) and the server
 * dispatcher (reading a {@code @CpuRestController}'s methods) — same
 * annotations, same extraction, one implementation.
 */
public record RouteMeta(int method, String path) {

    public static Optional<RouteMeta> of(Method m) {
        CpuGet get = m.getAnnotation(CpuGet.class);
        if (get != null) {
            return Optional.of(new RouteMeta(HeaderLayout.METHOD_GET, get.value()));
        }
        CpuPost post = m.getAnnotation(CpuPost.class);
        if (post != null) {
            return Optional.of(new RouteMeta(HeaderLayout.METHOD_POST, post.value()));
        }
        CpuPut put = m.getAnnotation(CpuPut.class);
        if (put != null) {
            return Optional.of(new RouteMeta(HeaderLayout.METHOD_PUT, put.value()));
        }
        CpuDelete delete = m.getAnnotation(CpuDelete.class);
        if (delete != null) {
            return Optional.of(new RouteMeta(HeaderLayout.METHOD_DELETE, delete.value()));
        }
        return Optional.empty();
    }

    public static String methodName(int method) {
        return switch (method) {
            case HeaderLayout.METHOD_GET -> "GET";
            case HeaderLayout.METHOD_POST -> "POST";
            case HeaderLayout.METHOD_PUT -> "PUT";
            case HeaderLayout.METHOD_DELETE -> "DELETE";
            default -> "UNKNOWN(" + method + ")";
        };
    }
}
