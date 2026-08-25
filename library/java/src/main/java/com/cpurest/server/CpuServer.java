package com.cpurest.server;

import com.cpurest.annotations.CpuRestController;
import com.cpurest.transport.Bus;
import com.cpurest.util.RouteMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Register one or more {@code @CpuRestController}-annotated instances, then
 * {@link #start()}: one bus is created (and owned) per distinct {@code bus()}
 * named across the registered controllers, each served by its own
 * background daemon thread.
 */
public final class CpuServer {
    private final Map<String, List<RouteHandlerBinding>> bindingsByBus = new LinkedHashMap<>();
    private final List<ServerWorker> workers = new ArrayList<>();

    public CpuServer register(Object controller) {
        CpuRestController annotation = controller.getClass().getAnnotation(CpuRestController.class);
        if (annotation == null) {
            throw new IllegalArgumentException(controller.getClass().getName() + " must be annotated with @CpuRestController");
        }
        List<RouteHandlerBinding> bindings = bindingsByBus.computeIfAbsent(annotation.bus(), b -> new ArrayList<>());
        for (var method : controller.getClass().getMethods()) {
            RouteMeta.of(method).ifPresent(meta -> bindings.add(new RouteHandlerBinding(meta, controller, method)));
        }
        return this;
    }

    /** Spawn one background daemon thread per registered bus and start serving. */
    public CpuServer start() {
        for (var entry : bindingsByBus.entrySet()) {
            Bus bus = Bus.createServer(entry.getKey());
            ServerWorker worker = new ServerWorker(entry.getKey(), bus, entry.getValue());
            worker.start();
            workers.add(worker);
        }
        return this;
    }

    /** Stop every worker thread and release its bus (unmaps + unlinks the shm segment). */
    public void stop() {
        for (ServerWorker worker : workers) {
            worker.stop();
        }
        workers.clear();
    }
}
