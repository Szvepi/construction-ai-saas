package com.buildassist.dto;

import java.util.List;

public final class QuoteSetupDtos {

    private QuoteSetupDtos() {
    }

    public record QuoteSetupStatus(
            boolean companyProfileComplete,
            boolean catalogReady,
            boolean quoteGenerationEnabled,
            List<String> missingRequirements) {
    }
}
