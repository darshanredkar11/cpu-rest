package com.cpurest.server;

import com.cpurest.CpuException;
import com.cpurest.transport.Bus;
import com.cpurest.transport.HeaderLayout;
import com.cpurest.transport.RingChannel;
import com.cpurest.transport.WireMessage;
import com.cpurest.util.RouteMeta;
import com.cpurest.util.Wire;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

/**
 * Background daemon thread for one bus: polls the request channel, reflectively
 * dispatches to the bound controller method, and writes the response. Any
 * uncaught exception (other than {@link CpuException}, which sets its own
 * status) is mapped to HTTP 500 — a bug in one handler never kills the
 * thread or wedges the bus, matching the Rust server's panic-to-500 translation.
 */
final class ServerWorker {
    private static final Duration POLL_SLICE = Duration.ofMillis(200);
    private static final Duration RESPONSE_WRITE_TIMEOUT = Duration.ofSeconds(5);

    private final String busName;
    private final Bus bus;
    private final List<RouteHandlerBinding> bindings;
    private volatile boolean running = true;
    private Thread thread;

    ServerWorker(String busName, Bus bus, List<RouteHandlerBinding> bindings) {
        this.busName = busName;
        this.bus = bus;
        this.bindings = bindings;
    }

    void start() {
        thread = new Thread(this::runLoop, "cpurest-server-" + busName);
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        bus.close();
    }

    private void runLoop() {
        RingChannel requestChannel = bus.requestChannel();
        RingChannel responseChannel = bus.responseChannel();

        while (running) {
            WireMessage request;
            try {
                request = requestChannel.waitAndRead(HeaderLayout.STATE_REQ_READY, POLL_SLICE);
            } catch (RingChannel.TransportTimeoutException e) {
                continue;
            }

            int status;
            byte[] body;
            RouteHandlerBinding binding = find(request.method(), request.route());
            if (binding == null) {
                status = 404;
                body = Wire.errorBody("no route for " + RouteMeta.methodName(request.method()) + " " + request.route());
            } else {
                try {
                    Object result = invoke(binding, request.payload());
                    status = 200;
                    body = (result == null) ? new byte[0] : Wire.toBytes(result, Wire.codecFor(result.getClass()));
                } catch (CpuException e) {
                    status = e.status();
                    body = Wire.errorBody(e.message());
                } catch (Throwable t) {
                    status = 500;
                    body = Wire.errorBody("handler threw: " + rootMessage(t));
                }
            }

            requestChannel.consume();
            responseChannel.write(HeaderLayout.STATE_RESP_READY, request.method(), status, request.route(), body, RESPONSE_WRITE_TIMEOUT);
        }
    }

    private RouteHandlerBinding find(int method, String route) {
        for (RouteHandlerBinding b : bindings) {
            if (b.meta().method() == method && b.meta().path().equals(route)) {
                return b;
            }
        }
        return null;
    }

    private static Object invoke(RouteHandlerBinding binding, byte[] payload) throws Throwable {
        Method method = binding.method();
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args;
        if (paramTypes.length == 0) {
            args = new Object[0];
        } else if (paramTypes.length == 1) {
            args = new Object[] {Wire.fromBytes(payload, Wire.codecFor(paramTypes[0]))};
        } else {
            throw CpuException.internal("handler " + method + " must take zero or one argument");
        }
        try {
            return method.invoke(binding.controller(), args);
        } catch (InvocationTargetException e) {
            throw (e.getCause() != null) ? e.getCause() : e;
        }
    }

    private static String rootMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
