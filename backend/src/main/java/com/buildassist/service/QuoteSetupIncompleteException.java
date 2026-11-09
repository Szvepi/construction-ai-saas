package com.buildassist.service;

import java.util.List;

public class QuoteSetupIncompleteException extends RuntimeException {

    private final List<String> missingRequirements;

    public QuoteSetupIncompleteException(List<String> missingRequirements) {
        super("Az AI-ajánlatkészítéshez előbb töltsd ki a céges adatokat és hozz létre legalább egy érvényes, pozitív áras katalógustételt.");
        this.missingRequirements = List.copyOf(missingRequirements);
    }

    public List<String> getMissingRequirements() {
        return missingRequirements;
    }
}
