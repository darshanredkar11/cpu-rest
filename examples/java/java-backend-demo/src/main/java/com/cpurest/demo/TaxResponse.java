package com.cpurest.demo;

import json.JsonName;
import json.JsonRecord;

/** Must mirror tax-engine-rust's {@code TaxResponse} field-for-field. */
@JsonRecord
public record TaxResponse(@JsonName("tax_owed") double taxOwed, @JsonName("effective_rate") double effectiveRate) {}
