package com.buildassist.service;

import com.buildassist.dto.AiDtos.AiExtractionResult;
import com.buildassist.dto.AiDtos.AiExtractedItem;
import com.buildassist.dto.AiDtos.AiRequestedWork;
import com.buildassist.model.CalculationStrategy;
import com.buildassist.model.CatalogItem;
import com.buildassist.repository.CatalogItemRepository;
import com.buildassist.repository.CompanyProfileRepository;
import com.buildassist.repository.EmailDraftRepository;
import com.buildassist.repository.EmailRepository;
import com.buildassist.repository.GmailConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class AiDraftServiceQuantityTest {

    @Mock private ChatModel chatModel;
    @Mock private EmailRepository emailRepository;
    @Mock private CatalogItemRepository catalogItemRepository;
    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock private GmailConnectionRepository gmailConnectionRepository;
    @Mock private EmailDraftRepository emailDraftRepository;
    @Mock private TokenEncryptionService tokenEncryptionService;
    @Mock private SpringTemplateEngine templateEngine;
    @Mock private QuotePdfService quotePdfService;
    @Mock private QuoteEmailMessageFactory quoteEmailMessageFactory;

    private AiDraftService service;

    @BeforeEach
    void setUp() {
        service = new AiDraftService(chatModel, emailRepository, catalogItemRepository,
                companyProfileRepository, gmailConnectionRepository, emailDraftRepository,
                tokenEncryptionService, templateEngine, quotePdfService, quoteEmailMessageFactory,
                new QuoteCoverageService(new ObjectMapper()));
    }

    @Test
    void keepsExplicitThirtyLinearMetersInsteadOfEstimatingAreaPerimeter() {
        AiDraftService.PricingResult result = price(3L, "Szegélykő", "m",
                CalculationStrategy.ROOM_PERIMETER, new BigDecimal("30"), "m");

        assertEquals(new BigDecimal("30"), result.pricing().items().get(0).quantity());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void calculatesFifteenMetersOnTwoSidesAsThirtyLinearMeters() {
        BigDecimal bothSides = new BigDecimal("15").multiply(BigDecimal.valueOf(2));
        AiDraftService.PricingResult result = price(3L, "Szegélykő", "m",
                CalculationStrategy.ROOM_PERIMETER, bothSides, "fm");

        assertEquals(new BigDecimal("30"), result.pricing().items().get(0).quantity());
    }

    @Test
    void estimatesPerimeterOnlyFromSquareMeters() {
        AiDraftService.PricingResult result = price(3L, "Szegélykő", "m",
                CalculationStrategy.ROOM_PERIMETER, new BigDecimal("30"), "m²");

        assertEquals(new BigDecimal("21.91"), result.pricing().items().get(0).quantity());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void directStrategyKeepsAQuantityInItsCatalogUnit() {
        AiDraftService.PricingResult result = price(1L, "Földmunka", "m2",
                CalculationStrategy.DIRECT, new BigDecimal("30"), "m2");

        assertEquals(new BigDecimal("30"), result.pricing().items().get(0).quantity());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void incompatibleOrUnknownUnitsDoNotTriggerAnAutomaticConversion() {
        AiDraftService.PricingResult incompatible = price(3L, "Szegélykő", "m",
                CalculationStrategy.ROOM_PERIMETER, new BigDecimal("30"), "m3");
        AiDraftService.PricingResult unknown = price(3L, "Szegélykő", "m",
                CalculationStrategy.ROOM_PERIMETER, new BigDecimal("30"), "ismeretlen");

        assertEquals(new BigDecimal("30"), incompatible.pricing().items().get(0).quantity());
        assertEquals(new BigDecimal("30"), unknown.pricing().items().get(0).quantity());
        assertEquals(1, incompatible.warnings().size());
        assertEquals(1, unknown.warnings().size());
    }

    @Test
    void keepsTenPercentAreaAllowance() {
        AiDraftService.PricingResult result = price(4L, "Térkő", "m2",
                CalculationStrategy.WASTE_PERCENTAGE_10, new BigDecimal("45"), "m²");

        assertEquals(new BigDecimal("49.50"), result.pricing().items().get(0).quantity());
    }

    @Test
    void calculatesVolumeFromAreaAndThickness() {
        AiDraftService.PricingResult result = price(2L, "Zúzottkő", "m3",
                CalculationStrategy.VOLUME_BY_THICKNESS, new BigDecimal("45"), "m2");

        assertEquals(new BigDecimal("6.75"), result.pricing().items().get(0).quantity());
    }

    private AiDraftService.PricingResult price(
            Long catalogId, String name, String outputUnit, CalculationStrategy strategy,
            BigDecimal quantity, String inputUnit) {
        CatalogItem catalogItem = new CatalogItem();
        catalogItem.setId(catalogId);
        catalogItem.setName(name);
        catalogItem.setUnit(outputUnit);
        catalogItem.setUnitPrice(BigDecimal.ONE);
        catalogItem.setCalculationStrategy(strategy);

        AiExtractionResult extraction = new AiExtractionResult(
                null,
                List.of(new AiExtractedItem(catalogId, quantity)),
                List.of(),
                List.of(),
                List.of(new AiRequestedWork(name, "EMAIL", catalogId, quantity, inputUnit, null)));
        return service.calculatePricing(extraction, List.of(catalogItem));
    }
}
