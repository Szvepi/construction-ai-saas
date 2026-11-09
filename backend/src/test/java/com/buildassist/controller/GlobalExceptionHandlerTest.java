package com.buildassist.controller;

import com.buildassist.service.QuoteSetupIncompleteException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void quoteSetupFailureReturnsStructuredUnprocessableEntity() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        QuoteSetupIncompleteException exception =
                new QuoteSetupIncompleteException(List.of("COMPANY_PROFILE", "CATALOG"));

        var response = handler.handleQuoteSetupIncomplete(exception);
        Map<String, Object> body = response.getBody();

        assertEquals(422, response.getStatusCode().value());
        assertEquals("QUOTE_SETUP_INCOMPLETE", body.get("code"));
        assertEquals(List.of("COMPANY_PROFILE", "CATALOG"), body.get("missingRequirements"));
        assertEquals(exception.getMessage(), body.get("error"));
    }
}
