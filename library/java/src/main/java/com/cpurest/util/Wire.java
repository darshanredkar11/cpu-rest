package com.cpurest.util;

import json.Json;
import json.JsonCodec;
import json.JsonField;
import json.JsonReader;
import json.JsonWriter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wire-format bridge between cpurest's reflective route dispatch and
 * json-serializer's no-reflection codecs.
 *
 * <p>json-serializer deliberately has no {@code Class<T>}-driven generic
 * (de)serialization — every type needs a hand-written {@code JsonCodec<T>},
 * matched to the project's own no-reflection, no-magic design (see
 * {@code /Users/darshanredkar/darshan/json-serializer}). cpurest's
 * {@code @CpuClient}/{@code @CpuRestController} dispatch is unavoidably
 * reflective already (it has to invoke an arbitrary annotated method), so the
 * one place reflection is reintroduced is here, and only for a single
 * one-time lookup per type: every request/response DTO must be a
 * <b>public</b> type exposing a {@code public static final json.JsonCodec<T>
 * CODEC} field (exactly json-serializer's own documented convention, e.g.
 * {@code User.CODEC} in its README) — public, the same way an
 * {@code @CpuClient} interface already has to be public for
 * {@link java.lang.reflect.Proxy} to implement it. A package-private DTO
 * with a {@code public} CODEC field still isn't reachable: {@link Field#get}
 * requires the *declaring class* to be accessible too, and this bridge
 * deliberately never calls {@code setAccessible} to force past that — a
 * DTO you can't otherwise touch reflectively from outside its package
 * shouldn't become touchable just because cpurest wants its CODEC field.
 * That field is located once via {@link Class#getField} and cached forever —
 * the actual encode/decode work on every call goes straight through the
 * hand-written codec, with no further reflection.
 *
 * <p>Wire field names are whatever each codec's {@link JsonField}s spell out.
 * Rust's {@code serde} serializes struct fields as-written (snake_case,
 * e.g. {@code tax_owed}); matching that on the Java side is simply a matter
 * of writing {@code JsonField.of("tax_owed")} in the codec rather than
 * {@code JsonField.of("taxOwed")} — there is no automatic naming-strategy
 * layer (json-serializer doesn't have one), so getting this right is on
 * each codec author, the same way it would be for a Rust {@code #[serde(rename)]}.
 */
public final class Wire {
    private Wire() {}

    private static final Map<Class<?>, JsonCodec<?>> CODECS = new ConcurrentHashMap<>();

    /**
     * Locates and caches {@code type}'s codec, trying two conventions in
     * order: a hand-written {@code public static final JsonCodec<type> CODEC}
     * field directly on {@code type}, then — since a compile-time-generated
     * annotation processor can only emit a <em>new</em> file, never inject a
     * field into the type it was told to process — a {@code <Type>Codec}
     * sibling class in the same package, as produced by
     * {@code @json.JsonRecord} (see that annotation's javadoc).
     */
    public static JsonCodec<?> codecFor(Class<?> type) {
        return CODECS.computeIfAbsent(type, Wire::lookupCodec);
    }

    private static JsonCodec<?> lookupCodec(Class<?> type) {
        Field direct = tryGetCodecField(type);
        if (direct != null) {
            return readCodecField(direct, type);
        }
        Class<?> generated = tryLoadClass(type.getName() + "Codec");
        if (generated != null) {
            Field generatedField = tryGetCodecField(generated);
            if (generatedField != null) {
                return readCodecField(generatedField, generated);
            }
        }
        throw new WireException(
                "cpurest requires either a `public static final json.JsonCodec<" + type.getSimpleName()
                        + "> CODEC` field directly on " + type.getName() + ", or that type annotated with "
                        + "@json.JsonRecord (which generates " + type.getSimpleName()
                        + "Codec.CODEC) — neither was found");
    }

    private static Field tryGetCodecField(Class<?> holder) {
        try {
            return holder.getField("CODEC");
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Class<?> tryLoadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static JsonCodec<?> readCodecField(Field field, Class<?> holder) {
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new WireException(holder.getName() + ".CODEC must be static");
        }
        if (!JsonCodec.class.isAssignableFrom(field.getType())) {
            throw new WireException(holder.getName() + ".CODEC must be a json.JsonCodec<?>");
        }
        try {
            Object codec = field.get(null);
            if (codec == null) {
                throw new WireException(holder.getName() + ".CODEC is null");
            }
            return (JsonCodec<?>) codec;
        } catch (IllegalAccessException e) {
            throw new WireException("could not read " + holder.getName() + ".CODEC", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static byte[] toBytes(Object value, JsonCodec<?> codec) {
        try {
            return Json.toBytes((JsonCodec<Object>) codec, value);
        } catch (Exception e) {
            throw new WireException("failed to serialize " + value.getClass().getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T fromBytes(byte[] bytes, JsonCodec<?> codec) {
        try {
            return (T) Json.parse((JsonCodec<Object>) codec, bytes);
        } catch (Exception e) {
            throw new WireException("failed to deserialize with " + codec.getClass().getName(), e);
        }
    }

    // Fixed {"error": "..."} shape used for every non-2xx response body: known
    // ahead of time, so unlike a generic tree/JsonNode parser (which
    // json-serializer doesn't have), a small dedicated codec is all this needs.
    private static final JsonField ERROR_FIELD = JsonField.of("error");

    private static final JsonCodec<String> ERROR_BODY_CODEC = new JsonCodec<String>() {
        @Override
        public void write(JsonWriter w, String message) {
            w.beginObject();
            w.name(ERROR_FIELD).value(message);
            w.endObject();
        }

        @Override
        public String read(JsonReader r) {
            String message = null;
            r.beginObject();
            while (r.nextKey()) {
                if (r.keyIs(ERROR_FIELD)) {
                    message = r.readString();
                } else {
                    r.skipValue();
                }
            }
            return message;
        }
    };

    public static byte[] errorBody(String message) {
        try {
            return Json.toBytes(ERROR_BODY_CODEC, message);
        } catch (Exception e) {
            // Falls back to hand-built JSON if even the tiny error codec fails,
            // so an encoding bug never prevents an error from reaching the peer.
            String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
            return ("{\"error\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
        }
    }

    public static String extractErrorMessage(byte[] bytes) {
        try {
            String message = Json.parse(ERROR_BODY_CODEC, bytes);
            return (message != null) ? message : new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static final class WireException extends RuntimeException {
        public WireException(String message) {
            super(message);
        }

        public WireException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
