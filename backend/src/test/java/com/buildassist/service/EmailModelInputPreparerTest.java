package com.buildassist.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailModelInputPreparerTest {

    @Test
    void shortReplyIsPreserved() {
        String reply = "Igen, kérem küldjön ajánlatot.";

        assertEquals(reply, EmailModelInputPreparer.prepare(reply, 100));
    }

    @Test
    void removesHtmlNoiseAndSignatureWithoutChangingStoredOriginal() {
        String original = """
                <html><body><p>Új fürdőszobára kérek ajánlatot.</p>
                <script>ignore this script</script><div>Részletek egyeztethetők.</div>
                <p>--</p><p>Régi aláírás</p></body></html>
                """;

        String prepared = EmailModelInputPreparer.prepare(original, 100);

        assertTrue(prepared.contains("Új fürdőszobára kérek ajánlatot."));
        assertTrue(prepared.contains("Részletek egyeztethetők."));
        assertFalse(prepared.contains("<html"));
        assertFalse(prepared.contains("ignore this script"));
        assertFalse(prepared.contains("Régi aláírás"));
        assertTrue(original.contains("<html"));
    }

    @Test
    void keepsCurrentMessageAndMarksQuotedHistory() {
        String body = """
                Szeretnék ajánlatot kérni új tetőfedési munkára.

                On Monday, customer@example.hu wrote:
                > Korábban egy másik munkára kértem ajánlatot.
                """;

        String prepared = EmailModelInputPreparer.prepare(body, 200);

        assertTrue(prepared.startsWith("Szeretnék ajánlatot kérni új tetőfedési munkára."));
        assertTrue(prepared.contains("[Quoted previous message]"));
        assertTrue(prepared.contains("Korábban egy másik munkára kértem ajánlatot."));
        assertTrue(OpenAiTokenCounter.count(prepared) <= 200);
    }

    @Test
    void recognizesBlockquoteWithoutReplyHeaderAndKeepsQuotedContextAfterSignature() {
        String body = """
                Kérem az új munka árajánlatát.
                --
                Customer name
                > Korábban ezt a munkát már megbeszéltük.
                """;

        String prepared = EmailModelInputPreparer.prepare(body, 200);

        assertTrue(prepared.contains("[Quoted previous message]"));
        assertTrue(prepared.contains("Korábban ezt a munkát már megbeszéltük."));
        assertFalse(prepared.contains("Customer name"));
    }

    @Test
    void longForwardedMessageKeepsBeginningAndTailWithinTokenLimit() {
        String body = "Begin forwarded message:\n"
                + "Headers and previous text. ".repeat(1_000)
                + "\nThe forwarded customer requests a new roof quote.";

        String prepared = EmailModelInputPreparer.prepare(body, 100);

        assertTrue(prepared.startsWith("Begin forwarded message:"));
        assertTrue(prepared.contains("The forwarded customer requests a new roof quote."));
        assertTrue(prepared.contains("[Middle of forwarded message omitted]"));
        assertTrue(OpenAiTokenCounter.count(prepared) <= 100);
    }
}
