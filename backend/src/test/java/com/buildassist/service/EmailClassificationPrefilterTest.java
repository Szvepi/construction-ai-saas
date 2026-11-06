package com.buildassist.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailClassificationPrefilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void noreplyAddressAndRealQuoteRequestAreNotPreFiltered() {
        assertFalse(EmailClassificationPrefilter.evaluate("{}", objectMapper).excluded());
    }

    @Test
    void webContactFormWithoutAutomatedListHeadersIsNotPreFiltered() {
        assertFalse(EmailClassificationPrefilter.evaluate(
                "{\"Content-Type\":\"text/plain; charset=UTF-8\"}", objectMapper).excluded());
    }

    @Test
    void newsletterWithMultipleConfirmingListHeadersIsPreFiltered() {
        EmailClassificationPrefilter.Result result = EmailClassificationPrefilter.evaluate("""
                {
                  "List-Id":"Weekly offers",
                  "List-Unsubscribe":"<mailto:unsubscribe@example.com>",
                  "Precedence":"bulk"
                }
                """, objectMapper);

        assertTrue(result.excluded());
        org.junit.jupiter.api.Assertions.assertEquals("NEWSLETTER_HEADERS_V1", result.ruleId());
    }

    @Test
    void incompleteMarketingHeadersStillGoToAi() {
        assertFalse(EmailClassificationPrefilter.evaluate("""
                {"List-Unsubscribe":"<mailto:unsubscribe@example.com>"}
                """, objectMapper).excluded());
        assertFalse(EmailClassificationPrefilter.evaluate("""
                {"List-Id":"Customers"}
                """, objectMapper).excluded());
        assertFalse(EmailClassificationPrefilter.evaluate("""
                {"List-Id":"Discussion group","List-Unsubscribe":"<mailto:unsubscribe@example.com>",
                 "List-Post":"<mailto:group@example.com>"}
                """, objectMapper).excluded());
    }

    @Test
    void explicitDeliveryFailureHeadersArePreFiltered() {
        assertTrue(EmailClassificationPrefilter.evaluate("""
                {"Content-Type":"multipart/report; report-type=delivery-status"}
                """, objectMapper).excluded());
    }

    @Test
    void actionAndStatusHeadersAloneDoNotExcludeAnEmail() {
        assertFalse(EmailClassificationPrefilter.evaluate("""
                {"Action":"completed","Status":"active"}
                """, objectMapper).excluded());
    }

    @Test
    void malformedAutomaticHeaderValuesDoNotMatchBySubstring() {
        assertFalse(EmailClassificationPrefilter.evaluate("""
                {"Auto-Submitted":"not-auto-replied","X-Autoreply":"yesterday"}
                """, objectMapper).excluded());
    }

    @Test
    void automaticReplyHeaderIsPreFiltered() {
        EmailClassificationPrefilter.Result result = EmailClassificationPrefilter.evaluate(
                "{\"Auto-Submitted\":\"auto-replied\"}", objectMapper);

        assertTrue(result.excluded());
        org.junit.jupiter.api.Assertions.assertEquals("AUTO_REPLY_V1", result.ruleId());
    }

    @Test
    void mixedInvoiceAndNewWorkRequestIsNotPreFiltered() {
        assertFalse(EmailClassificationPrefilter.evaluate("{}", objectMapper).excluded());
    }

    @Test
    void quotedOldQuoteRequestIsNotPreFiltered() {
        assertFalse(EmailClassificationPrefilter.evaluate(
                "{\"In-Reply-To\":\"<previous@example.com>\"}", objectMapper).excluded());
    }

    @Test
    void malformedMetadataFailsOpenToAi() {
        assertFalse(EmailClassificationPrefilter.evaluate("{not-json", objectMapper).excluded());
    }

    @Test
    void headerRegressionSampleExcludesOnlyThreeOfElevenMessages() {
        List<String> messages = List.of(
                """
                {"List-Id":"Weekly offers","List-Unsubscribe":"<mailto:unsubscribe@example.com>",
                 "Precedence":"bulk"}
                """,
                "{\"Content-Type\":\"multipart/report; report-type=delivery-status\"}",
                "{\"Auto-Submitted\":\"auto-replied\"}",
                "{}",
                "{\"Content-Type\":\"text/plain; charset=UTF-8\"}",
                "{\"List-Unsubscribe\":\"<mailto:unsubscribe@example.com>\"}",
                "{\"List-Id\":\"Customers\"}",
                """
                {"List-Id":"Discussion group","List-Unsubscribe":"<mailto:unsubscribe@example.com>",
                 "List-Post":"<mailto:group@example.com>"}
                """,
                "{\"In-Reply-To\":\"<previous@example.com>\"}",
                "{not-json",
                "{\"Precedence\":\"bulk\"}");

        long excluded = messages.stream()
                .filter(metadata -> EmailClassificationPrefilter.evaluate(metadata, objectMapper).excluded())
                .count();

        assertEquals(11, messages.size());
        assertEquals(3, excluded);
    }
}
