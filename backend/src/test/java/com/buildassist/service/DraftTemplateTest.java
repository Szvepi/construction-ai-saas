package com.buildassist.service;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftTemplateTest {

    @Test
    void rendersSupplementaryNotesAsEscapedCustomerVisibleText() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        Context context = new Context(Locale.forLanguageTag("hu-HU"));
        context.setVariable("clientName", "Megrendelő");
        context.setVariable("lines", List.of());
        context.setVariable("grandTotal", "0");
        context.setVariable("offerorName", "");
        context.setVariable("offerorPhone", "");
        context.setVariable("additionalNotes", "<b>Telefonos egyeztetés</b>\n35 m²");

        String rendered = templateEngine.process("draft", context);

        assertTrue(rendered.contains("Kiegészítő megjegyzés"));
        assertTrue(rendered.contains("&lt;b&gt;Telefonos egyeztetés&lt;/b&gt;"));
        assertTrue(rendered.contains("35 m²"));
        assertFalse(rendered.contains("<b>Telefonos egyeztetés</b>"));
    }
}
