package com.cpurest.demo;

import json.JsonCodec;
import json.JsonField;
import json.JsonReader;
import json.JsonWriter;

/** Must mirror tax-engine-rust's {@code TaxResponse} field-for-field. */
public record TaxResponse(double taxOwed, double effectiveRate) {

    // Rust's serde serializes struct fields as-written (snake_case); json-serializer
    // has no automatic naming-strategy layer, so matching that wire format is just
    // a matter of spelling the snake_case name here rather than the Java field name.
    private static final JsonField TAX_OWED = JsonField.of("tax_owed");
    private static final JsonField EFFECTIVE_RATE = JsonField.of("effective_rate");

    public static final JsonCodec<TaxResponse> CODEC = new JsonCodec<TaxResponse>() {
        @Override
        public void write(JsonWriter w, TaxResponse v) {
            w.beginObject();
            w.name(TAX_OWED).value(v.taxOwed());
            w.name(EFFECTIVE_RATE).value(v.effectiveRate());
            w.endObject();
        }

        @Override
        public TaxResponse read(JsonReader r) {
            double taxOwed = 0;
            double effectiveRate = 0;
            r.beginObject();
            while (r.nextKey()) {
                if (r.keyIs(TAX_OWED)) taxOwed = r.readDouble();
                else if (r.keyIs(EFFECTIVE_RATE)) effectiveRate = r.readDouble();
                else r.skipValue();
            }
            return new TaxResponse(taxOwed, effectiveRate);
        }
    };
}
