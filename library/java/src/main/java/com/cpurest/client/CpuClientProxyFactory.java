package com.cpurest.client;

import com.cpurest.CpuException;
import com.cpurest.annotations.CpuClient;
import com.cpurest.transport.Bus;
import com.cpurest.transport.HeaderLayout;
import com.cpurest.transport.WireMessage;
import com.cpurest.util.RouteMeta;
import com.cpurest.util.Wire;
import json.JsonCodec;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Builds a {@link Proxy} implementing a {@code @CpuClient}-annotated
 * interface, dispatching each {@code @CpuGet}/{@code @CpuPost}/{@code @CpuPut}/
 * {@code @CpuDelete} method as a synchronous shared-memory round trip.
 */
public final class CpuClientProxyFactory {
    private CpuClientProxyFactory() {}

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> clientInterface) {
        CpuClient annotation = clientInterface.getAnnotation(CpuClient.class);
        if (annotation == null) {
            throw new IllegalArgumentException(clientInterface.getName() + " must be annotated with @CpuClient");
        }
        String busName = annotation.bus();
        Bus bus = ClientBusRegistry.get(busName);
        ReentrantLock callLock = ClientBusRegistry.lockFor(busName);

        Map<Method, RouteMeta> routes = new HashMap<>();
        Map<Method, JsonCodec<?>> requestCodecs = new HashMap<>();
        Map<Method, JsonCodec<?>> responseCodecs = new HashMap<>();
        for (Method m : clientInterface.getMethods()) {
            RouteMeta.of(m).ifPresent(rm -> {
                routes.put(m, rm);
                // Codec lookup (Wire.codecFor -> Class.getField("CODEC")) happens
                // once here, at proxy-build time — every call after this is a
                // straight hand-written-codec encode/decode, no more reflection.
                Class<?>[] paramTypes = m.getParameterTypes();
                if (paramTypes.length == 1) {
                    requestCodecs.put(m, Wire.codecFor(paramTypes[0]));
                }
                if (m.getReturnType() != void.class) {
                    responseCodecs.put(m, Wire.codecFor(m.getReturnType()));
                }
            });
        }

        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(proxy, method, args);
            }
            RouteMeta route = routes.get(method);
            if (route == null) {
                throw new UnsupportedOperationException(
                        "no @CpuGet/@CpuPost/@CpuPut/@CpuDelete annotation on " + method);
            }
            byte[] requestPayload = (args != null && args.length > 0)
                    ? Wire.toBytes(args[0], requestCodecs.get(method))
                    : new byte[0];

            callLock.lock();
            try {
                bus.requestChannel()
                        .write(HeaderLayout.STATE_REQ_READY, route.method(), 0, route.path(), requestPayload, RESPONSE_TIMEOUT);
                WireMessage response = bus.responseChannel().waitAndRead(HeaderLayout.STATE_RESP_READY, RESPONSE_TIMEOUT);
                bus.responseChannel().consume();

                if (response.status() >= 200 && response.status() < 300) {
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    return Wire.fromBytes(response.payload(), responseCodecs.get(method));
                }
                throw new CpuException(response.status(), Wire.extractErrorMessage(response.payload()));
            } finally {
                callLock.unlock();
            }
        };

        return (T) Proxy.newProxyInstance(clientInterface.getClassLoader(), new Class<?>[] {clientInterface}, handler);
    }

    private static Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "CpuClientProxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args != null ? args[0] : null);
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }
}
