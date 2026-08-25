package com.cpurest.demo;

import json.JsonCodec;
import json.JsonField;
import json.JsonReader;
import json.JsonWriter;

/** Must mirror tax-engine-rust's {@code TaxRequest} field-for-field — JSON is structural, not typed across the bus. */
public record TaxRequest(double income, double deductions) {

    private static final JsonField INCOME = JsonField.of("income");
    private static final JsonField DEDUCTIONS = JsonField.of("deductions");

    public static final JsonCodec<TaxRequest> CODEC = new JsonCodec<TaxRequest>() {
        @Override
        public void write(JsonWriter w, TaxRequest v) {
            w.beginObject();
            w.name(INCOME).value(v.income());
            w.name(DEDUCTIONS).value(v.deductions());
            w.endObject();
        }

        @Override
        public TaxRequest read(JsonReader r) {
            double income = 0;
            double deductions = 0;
            r.beginObject();
            while (r.nextKey()) {
                if (r.keyIs(INCOME)) income = r.readDouble();
                else if (r.keyIs(DEDUCTIONS)) deductions = r.readDouble();
                else r.skipValue();
            }
            return new TaxRequest(income, deductions);
        }
    };
}
