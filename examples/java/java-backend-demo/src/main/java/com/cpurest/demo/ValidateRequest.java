package com.cpurest.demo;

import json.JsonCodec;
import json.JsonField;
import json.JsonReader;
import json.JsonWriter;

/** Must mirror tax-engine-rust's {@code validate_client} binary's {@code ValidateRequest}. */
public record ValidateRequest(double income) {

    private static final JsonField INCOME = JsonField.of("income");

    public static final JsonCodec<ValidateRequest> CODEC = new JsonCodec<ValidateRequest>() {
        @Override
        public void write(JsonWriter w, ValidateRequest v) {
            w.beginObject();
            w.name(INCOME).value(v.income());
            w.endObject();
        }

        @Override
        public ValidateRequest read(JsonReader r) {
            double income = 0;
            r.beginObject();
            while (r.nextKey()) {
                if (r.keyIs(INCOME)) income = r.readDouble();
                else r.skipValue();
            }
            return new ValidateRequest(income);
        }
    };
}
