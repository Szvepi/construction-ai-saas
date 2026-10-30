package com.buildassist.calculation;

import java.util.UUID;

public final class QuoteIdGenerator {

    public String newWorkId() {
        return "work-" + UUID.randomUUID();
    }

    public String newLineItemId() {
        return "line-" + UUID.randomUUID();
    }
}
