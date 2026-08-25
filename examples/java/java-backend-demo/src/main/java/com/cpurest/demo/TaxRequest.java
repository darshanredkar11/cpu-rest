package com.cpurest.demo;

import json.JsonRecord;

/** Must mirror tax-engine-rust's {@code TaxRequest} field-for-field — JSON is structural, not typed across the bus. */
@JsonRecord
public record TaxRequest(double income, double deductions) {}
