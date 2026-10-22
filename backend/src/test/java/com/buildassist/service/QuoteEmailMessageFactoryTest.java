package com.buildassist.service;

import com.buildassist.model.CompanyProfile;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteEmailMessageFactoryTest {

    @Test
    void createsPlainEmailWithPdfAttachmentAndOfferorSignature() throws Exception {
        CompanyProfile profile = new CompanyProfile();
        profile.setOfferorName("Kovács János");
        profile.setOfferorPhone("+36 30 123 4567");
        byte[] pdf = "%PDF-test".getBytes(StandardCharsets.US_ASCII);

        MimeMessage message = new QuoteEmailMessageFactory().create(
            "sender@example.com",
            "client@example.com",
            "Re: Árajánlatkérés",
            pdf,
            "arajanlat-12.pdf",
            "Teszt Megrendelő",
            profile
        );

        MimeMultipart multipart = (MimeMultipart) message.getContent();
        assertEquals(2, multipart.getCount());
        assertTrue(((String) multipart.getBodyPart(0).getContent())
            .contains("Tisztelt Teszt Megrendelő!"));
        assertTrue(((String) multipart.getBodyPart(0).getContent()).contains("Kovács János"));

        MimeBodyPart attachment = (MimeBodyPart) multipart.getBodyPart(1);
        assertTrue(attachment.getContentType().startsWith("application/pdf"));
        assertEquals("arajanlat-12.pdf", attachment.getFileName());
        assertArrayEquals(pdf, attachment.getInputStream().readAllBytes());
        assertEquals("client@example.com", message.getRecipients(MimeMessage.RecipientType.TO)[0].toString());
    }
}
