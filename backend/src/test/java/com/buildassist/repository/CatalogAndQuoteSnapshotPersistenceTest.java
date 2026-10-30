package com.buildassist.repository;

import com.buildassist.calculation.*;
import com.buildassist.dto.CatalogDtos.CatalogItemResponse;
import com.buildassist.dto.CatalogDtos.CreateCatalogItemRequest;
import com.buildassist.dto.CatalogDtos.UpdateCatalogItemRequest;
import com.buildassist.model.*;
import com.buildassist.service.CatalogItemValidator;
import com.buildassist.service.CatalogService;
import com.buildassist.service.DraftQuoteSnapshotCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CatalogService.class, CatalogItemValidator.class,
        CatalogAndQuoteSnapshotPersistenceTest.JsonTestConfiguration.class})
class CatalogAndQuoteSnapshotPersistenceTest {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private CatalogItemRepository catalogItemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GmailConnectionRepository gmailConnectionRepository;

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private EmailDraftRepository emailDraftRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void persistsCatalogTypesCostsConfigTenantIsolationAndFrozenDraftSnapshot() {
        User owner = saveUser("catalog-owner@example.test");
        User other = saveUser("catalog-other@example.test");
        List<CreateCatalogItemRequest> fixtures = CatalogTestFixtures.allTrades();
        List<CatalogItemResponse> created = fixtures.stream()
                .map(item -> catalogService.createItem(owner.getId(), item))
                .toList();

        assertEquals(16, created.size());
        assertEquals(List.of(CatalogItemType.MATERIAL, CatalogItemType.LABOR,
                        CatalogItemType.COMBINED, CatalogItemType.OTHER),
                created.stream().map(CatalogItemResponse::itemType).distinct().sorted().toList());
        assertEquals(16, catalogService.findItemsByUserId(owner.getId()).size());
        assertEquals(0, catalogService.findItemsByUserId(other.getId()).size());

        CatalogItemResponse material = created.stream()
                .filter(item -> item.itemType() == CatalogItemType.MATERIAL)
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("8500"), material.netProcurementUnitCost());
        assertNull(material.estimatedOwnLaborUnitCost());
        assertEquals(new BigDecimal("1234.50"), material.netSellingUnitPrice());
        assertEquals(new BigDecimal("15"),
                material.calculationConfig().defaultParameters().get("thickness").value());

        CatalogItemResponse updated = catalogService.updateItem(owner.getId(), material.id(),
                new UpdateCatalogItemRequest(
                        material.name(), new BigDecimal("1400"), material.unit(), material.itemType(),
                        new BigDecimal("9200"), null, material.calculationConfig()));
        assertEquals(new BigDecimal("1400"), updated.netSellingUnitPrice());
        assertEquals(new BigDecimal("9200"), updated.netProcurementUnitCost());
        assertThrows(IllegalArgumentException.class, () -> catalogService.updateItem(other.getId(),
                material.id(), new UpdateCatalogItemRequest(
                        material.name(), new BigDecimal("1400"), material.unit(), material.itemType(),
                        new BigDecimal("9200"), null, material.calculationConfig())));

        CreateCatalogItemRequest invalidMaterialCost = new CreateCatalogItemRequest(
                "Invalid material", new BigDecimal("100"), "m2", CatalogItemType.MATERIAL,
                new BigDecimal("40"), new BigDecimal("30"), null);
        assertThrows(IllegalArgumentException.class,
                () -> catalogService.createItem(owner.getId(), invalidMaterialCost));

        CatalogItemResponse snapshotMaterial = created.stream()
                .filter(item -> item.name().equals("Térkőanyag"))
                .findFirst().orElseThrow();
        persistDraftSnapshot(owner, snapshotMaterial);
    }

    @Test
    void preventsDuplicateLegacyAndStructuredQuoteLineSources() {
        User user = saveUser("snapshot-source@example.test");
        GmailConnection connection = new GmailConnection();
        connection.setUser(user);
        connection.setGmailAddress("snapshot-source@gmail.com");
        connection.setAccessTokenEncrypted("encrypted-access");
        connection.setRefreshTokenEncrypted("encrypted-refresh");
        connection.setTokenExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));
        connection = gmailConnectionRepository.save(connection);

        Email email = new Email();
        email.setGmailConnection(connection);
        email.setGmailMessageId("duplicate-line-source");
        email.setEmailReceivedAt(OffsetDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneOffset.UTC));
        email.setSyncedAt(OffsetDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneOffset.UTC));
        email.setCategory(EmailCategory.QUOTE_REQUEST);
        email = emailRepository.save(email);

        EmailDraft draft = new EmailDraft();
        draft.setEmail(email);
        draft.setDraftBody("Draft");
        draft.setLineItemsJson("[]");
        DraftQuoteSnapshotCodec codec = new DraftQuoteSnapshotCodec(objectMapper);
        draft.setQuoteSnapshotJson(codec.encode(codec.seal(
                new DraftQuoteSnapshot(DraftQuoteSnapshot.CURRENT_SCHEMA_VERSION,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        List.of(), List.of()))));

        assertThrows(DataIntegrityViolationException.class, () -> emailDraftRepository.saveAndFlush(draft));
    }

    private void persistDraftSnapshot(User owner, CatalogItemResponse originalCatalogItem) {
        GmailConnection connection = new GmailConnection();
        connection.setUser(owner);
        connection.setGmailAddress("catalog-owner@gmail.com");
        connection.setAccessTokenEncrypted("encrypted-access");
        connection.setRefreshTokenEncrypted("encrypted-refresh");
        connection.setTokenExpiresAt(Instant.parse("2027-01-01T00:00:00Z"));
        connection = gmailConnectionRepository.save(connection);

        Email email = new Email();
        email.setGmailConnection(connection);
        email.setGmailMessageId("quote-snapshot-test");
        email.setSubject("Paving work");
        email.setEmailReceivedAt(OffsetDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneOffset.UTC));
        email.setSyncedAt(OffsetDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneOffset.UTC));
        email.setCategory(EmailCategory.QUOTE_REQUEST);
        email = emailRepository.save(email);

        DraftQuoteSnapshot.CatalogItemSnapshot catalogSnapshot =
                new DraftQuoteSnapshot.CatalogItemSnapshot(
                        originalCatalogItem.name(),
                        originalCatalogItem.unit(),
                        originalCatalogItem.itemType(),
                        originalCatalogItem.netSellingUnitPrice(),
                        originalCatalogItem.netProcurementUnitCost(),
                        originalCatalogItem.estimatedOwnLaborUnitCost(),
                        originalCatalogItem.calculationConfig());
        Quantity requested = new Quantity(new BigDecimal("45"), QuantityUnit.SQUARE_METER);
        CalculationResult needsInput = new CalculationResult(
                CalculationStatus.NEEDS_INPUT, null,
                List.of(new CalculationIssue("NEEDS_INPUT", "Missing area.", "area")), List.of());
        DraftQuoteSnapshot snapshot = new DraftQuoteSnapshot(
                DraftQuoteSnapshot.CURRENT_SCHEMA_VERSION,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                List.of(
                        new DraftQuoteSnapshot.QuoteWorkSnapshot(
                                "work-driveway", "45 m2 kocsibeálló", InputSource.CUSTOMER_EMAIL,
                                requested, "m2", SettlementMode.MATERIAL_AND_LABOR,
                                WorkRecognitionStatus.MAPPED, originalCatalogItem.id(),
                                "45 m2 kocsibeálló", "Explicit driveway request.", true,
                                List.of(), List.of()),
                        new DraftQuoteSnapshot.QuoteWorkSnapshot(
                                "work-walkway", "12 m2 járda", InputSource.CUSTOMER_SUPPLEMENT,
                                new Quantity(new BigDecimal("12"), QuantityUnit.SQUARE_METER),
                                "m2", SettlementMode.MATERIAL_AND_LABOR,
                                WorkRecognitionStatus.MAPPED, originalCatalogItem.id(),
                                "12 m2 járda", "Explicit walkway request.", true,
                                List.of(), List.of())),
                List.of(
                        line("line-driveway", "work-driveway", originalCatalogItem, catalogSnapshot,
                                requested, needsInput),
                        line("line-walkway", "work-walkway", originalCatalogItem, catalogSnapshot,
                                new Quantity(new BigDecimal("12"), QuantityUnit.SQUARE_METER), needsInput)));
        DraftQuoteSnapshotCodec codec = new DraftQuoteSnapshotCodec(objectMapper);
        DraftQuoteSnapshot sealedSnapshot = codec.seal(snapshot);

        EmailDraft draft = new EmailDraft();
        draft.setEmail(email);
        draft.setDraftBody("Draft quote");
        draft.setQuoteSnapshotJson(codec.encode(sealedSnapshot));
        Long draftId = emailDraftRepository.saveAndFlush(draft).getId();

        CatalogItem catalogItem = catalogItemRepository.findById(originalCatalogItem.id()).orElseThrow();
        catalogItem.setNetSellingUnitPrice(new BigDecimal("9999"));
        catalogItemRepository.saveAndFlush(catalogItem);
        entityManager.clear();

        EmailDraft restoredDraft = emailDraftRepository.findById(draftId).orElseThrow();
        DraftQuoteSnapshot restored = codec.decode(restoredDraft.getQuoteSnapshotJson());
        assertEquals(sealedSnapshot, restored);
        assertEquals("work-driveway", restored.lineItems().getFirst().workId());
        assertEquals("line-driveway", restored.lineItems().getFirst().lineItemId());
        assertEquals("work-walkway", restored.lineItems().get(1).workId());
        assertEquals("line-walkway", restored.lineItems().get(1).lineItemId());
        assertEquals(new BigDecimal("1234.50"),
                restored.lineItems().getFirst().catalogItem().netSellingUnitPrice());
        assertEquals(CalculationStatus.VALID,
                restored.lineItems().getFirst().calculationResult().status());
        assertEquals(new BigDecimal("42"), restored.lineItems().getFirst().userOverrideQuantity().value());
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                restored.lineItems().getFirst().approvedFingerprint());
        assertFalse(restored.approvalIsCurrent(restored.lineItems().getFirst()));
    }

    private DraftQuoteSnapshot.QuoteLineSnapshot line(
            String lineId,
            String workId,
            CatalogItemResponse catalogItem,
            DraftQuoteSnapshot.CatalogItemSnapshot catalogSnapshot,
            Quantity required,
            CalculationResult result) {
        InputSource source = workId.equals("work-walkway")
                ? InputSource.CUSTOMER_SUPPLEMENT : InputSource.CUSTOMER_EMAIL;
        String evidence = workId.equals("work-walkway")
                ? "12 m2 járda" : "45 m2 kocsibeálló";
        CalculationPlan plan = new CalculationPlan(
                List.of(new CalculationInput("area", required.value(), "m2", source, true, evidence)),
                List.of(new CalculationStep("area-result", CalculationOperation.DIRECT,
                        List.of("area"), "m2", null, null)),
                "area-result", List.of());
        CalculationResult valid = new CalculationResult(
                CalculationStatus.VALID, required, List.of(), List.of());
        return new DraftQuoteSnapshot.QuoteLineSnapshot(
                lineId,
                workId,
                catalogItem.id(),
                catalogSnapshot,
                SettlementMode.MATERIAL_AND_LABOR,
                List.of(new DraftQuoteSnapshot.RequirementSnapshot(
                        "material-" + workId, ChargeComponent.MATERIAL, required,
                        new Quantity(new BigDecimal("49.5"), QuantityUnit.SQUARE_METER),
                        new Quantity(new BigDecimal("49.5"), QuantityUnit.SQUARE_METER),
                        catalogItem.netProcurementUnitCost(), null)),
                plan,
                valid,
                required,
                new Quantity(new BigDecimal("49.5"), QuantityUnit.SQUARE_METER),
                new Quantity(new BigDecimal("49.5"), QuantityUnit.SQUARE_METER),
                new Quantity(new BigDecimal("42"), QuantityUnit.SQUARE_METER),
                true,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "Reviewed manually.",
                new BigDecimal("74250"));
    }

    private User saveUser(String emailAddress) {
        User user = new User();
        user.setEmail(emailAddress);
        user.setPasswordHash("integration-test");
        user.setActive(true);
        return userRepository.save(user);
    }

    @TestConfiguration
    static class JsonTestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
