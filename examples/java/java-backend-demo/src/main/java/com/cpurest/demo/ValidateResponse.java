package com.cpurest.demo;

import json.Codecs;
import json.JsonCodec;
import json.JsonField;
import json.JsonReader;
import json.JsonWriter;

/** Must mirror tax-engine-rust's {@code validate_client} binary's {@code ValidateResponse}. */
public record ValidateResponse(boolean valid, String reason) {

    private static final JsonField VALID = JsonField.of("valid");
    private static final JsonField REASON = JsonField.of("reason");
    private static final JsonCodec<String> NULLABLE_STRING = Codecs.nullable(Codecs.STRING);

    public static final JsonCodec<ValidateResponse> CODEC = new JsonCodec<ValidateResponse>() {
        @Override
        public void write(JsonWriter w, ValidateResponse v) {
            w.beginObject();
            w.name(VALID).value(v.valid());
            w.name(REASON);
            NULLABLE_STRING.write(w, v.reason());
            w.endObject();
        }

        @Override
        public ValidateResponse read(JsonReader r) {
            boolean valid = false;
            String reason = null;
            r.beginObject();
            while (r.nextKey()) {
                if (r.keyIs(VALID)) valid = r.readBoolean();
                else if (r.keyIs(REASON)) reason = NULLABLE_STRING.read(r);
                else r.skipValue();
            }
            return new ValidateResponse(valid, reason);
        }
    };
}
