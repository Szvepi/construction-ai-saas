package com.buildassist.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotePdfServiceTest {

    @Test
    void rendersPdfWithHungarianText() throws Exception {
        QuotePdfService service = new QuotePdfService();

        byte[] pdf = service.render("""
            <html>
            <head><meta charset="UTF-8"/></head>
            <body style="font-family: 'DejaVu Sans';">
            <p>\u00C1rv\u00EDzt\u0171r\u0151 t\u00FCk\u00F6rf\u00FAr\u00F3g\u00E9p - K\u00F6zter\u00FClet, \u0151 \u00E9s \u0171.</p>
            </body>
            </html>
            """);

        assertTrue(new String(pdf, 0, 8, StandardCharsets.US_ASCII).startsWith("%PDF-"));
        assertTrue(pdf.length > 500);
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
            String extractedText = new PDFTextStripper().getText(document).replace('\u00A0', ' ');
            assertTrue(extractedText.contains("\u00C1rv\u00EDzt\u0171r\u0151 t\u00FCk\u00F6rf\u00FAr\u00F3g\u00E9p"), extractedText);
            assertTrue(extractedText.contains("\u0151 \u00E9s \u0171"), extractedText);
        }
    }
}
