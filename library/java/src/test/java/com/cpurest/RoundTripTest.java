package com.cpurest;

import com.cpurest.annotations.CpuClient;
import com.cpurest.annotations.CpuPost;
import com.cpurest.annotations.CpuRestController;
import com.cpurest.server.CpuServer;
import json.JsonCodec;
import json.JsonField;
import json.JsonReader;
import json.JsonWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end proof that the whole stack (annotated controller -&gt; shm bus
 * -&gt; typed client proxy) actually round-trips, entirely within one JVM
 * process (both the server daemon thread and the client proxy map the same
 * POSIX shm segment). One server/client pair is shared across the test
 * methods below: the client-side {@code Bus} connection is cached per bus
 * name for the process's lifetime (by design — a real client reconnects to
 * one long-lived server, not a series of short-lived ones), so starting and
 * tearing down a fresh same-named server per test method would leave later
 * tests talking to a stale mapping.
 */
class RoundTripTest {

    public record EchoRequest(long value) {
        static final JsonField VALUE = JsonField.of("value");
        public static final JsonCodec<EchoRequest> CODEC = new JsonCodec<EchoRequest>() {
            public void write(JsonWriter w, EchoRequest v) {
                w.beginObject();
                w.name(VALUE).value(v.value());
                w.endObject();
            }

            public EchoRequest read(JsonReader r) {
                long value = 0;
                r.beginObject();
                while (r.nextKey()) {
                    if (r.keyIs(VALUE)) value = r.readLong();
                    else r.skipValue();
                }
                return new EchoRequest(value);
            }
        };
    }

    public record EchoResponse(long doubled) {
        static final JsonField DOUBLED = JsonField.of("doubled");
        public static final JsonCodec<EchoResponse> CODEC = new JsonCodec<EchoResponse>() {
            public void write(JsonWriter w, EchoResponse v) {
                w.beginObject();
                w.name(DOUBLED).value(v.doubled());
                w.endObject();
            }

            public EchoResponse read(JsonReader r) {
                long doubled = 0;
                r.beginObject();
                while (r.nextKey()) {
                    if (r.keyIs(DOUBLED)) doubled = r.readLong();
                    else r.skipValue();
                }
                return new EchoResponse(doubled);
            }
        };
    }

    @CpuRestController(bus = "/cpurest_java_test_echo_v2")
    public static class EchoController {
        @CpuPost("/echo")
        public EchoResponse echo(EchoRequest req) {
            return new EchoResponse(req.value() * 2);
        }

        @CpuPost("/boom")
        public EchoResponse boom(EchoRequest req) {
            throw new RuntimeException("intentional handler failure for the test");
        }

        @CpuPost("/reject")
        public EchoResponse reject(EchoRequest req) {
            throw CpuException.badRequest("nope");
        }
    }

    @CpuClient(bus = "/cpurest_java_test_echo_v2")
    interface EchoClient {
        @CpuPost("/echo")
        EchoResponse echo(EchoRequest req);

        @CpuPost("/boom")
        EchoResponse boom(EchoRequest req);

        @CpuPost("/reject")
        EchoResponse reject(EchoRequest req);

        @CpuPost("/nonexistent")
        EchoResponse nonexistent(EchoRequest req);
    }

    private static CpuServer server;
    private static EchoClient client;

    @BeforeAll
    static void startServerAndClient() {
        server = CpuRest.server().register(new EchoController()).start();
        client = CpuRest.client(EchoClient.class);
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @Test
    void roundTripsATypedRequest() {
        EchoResponse response = client.echo(new EchoRequest(21));
        assertEquals(42, response.doubled());
    }

    @Test
    void handlerExceptionMapsTo500() {
        CpuException ex = assertThrows(CpuException.class, () -> client.boom(new EchoRequest(1)));
        assertEquals(500, ex.status());
    }

    @Test
    void cpuExceptionStatusPropagatesToClient() {
        CpuException ex = assertThrows(CpuException.class, () -> client.reject(new EchoRequest(1)));
        assertEquals(400, ex.status());
        assertEquals("400 nope", ex.getMessage());
    }

    @Test
    void unknownRouteReturns404() {
        CpuException ex = assertThrows(CpuException.class, () -> client.nonexistent(new EchoRequest(1)));
        assertEquals(404, ex.status());
    }
}
