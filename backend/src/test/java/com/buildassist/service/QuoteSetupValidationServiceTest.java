package com.buildassist.service;

import com.buildassist.calculation.CatalogItemType;
import com.buildassist.dto.QuoteSetupDtos.QuoteSetupStatus;
import com.buildassist.model.CatalogItem;
import com.buildassist.model.CompanyProfile;
import com.buildassist.model.User;
import com.buildassist.repository.CatalogItemRepository;
import com.buildassist.repository.CompanyProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuoteSetupValidationServiceTest {

    private static final Long USER_ID = 23L;

    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock private CatalogItemRepository catalogItemRepository;

    private QuoteSetupValidationService service;

    @BeforeEach
    void setUp() {
        service = new QuoteSetupValidationService(
                companyProfileRepository, catalogItemRepository, new ObjectMapper());
        when(companyProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(catalogItemRepository.findAllByUserId(USER_ID)).thenReturn(List.of());
    }

    @Test
    void newUserNeedsBothCompanyProfileAndCatalog() {
        QuoteSetupStatus status = service.getStatus(USER_ID);

        assertFalse(status.companyProfileComplete());
        assertFalse(status.catalogReady());
        assertFalse(status.quoteGenerationEnabled());
        assertEquals(List.of("COMPANY_PROFILE", "CATALOG"), status.missingRequirements());
    }

    @Test
    void completeProfileWithoutCatalogNeedsOnlyCatalog() {
        when(companyProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(completeProfile()));

        QuoteSetupStatus status = service.getStatus(USER_ID);

        assertTrue(status.companyProfileComplete());
        assertFalse(status.catalogReady());
        assertEquals(List.of("CATALOG"), status.missingRequirements());
    }

    @Test
    void validCatalogWithoutCompleteProfileNeedsOnlyCompanyProfile() {
        when(catalogItemRepository.findAllByUserId(USER_ID))
                .thenReturn(List.of(catalogItem(USER_ID, "1250", "db")));

        QuoteSetupStatus status = service.getStatus(USER_ID);

        assertFalse(status.companyProfileComplete());
        assertTrue(status.catalogReady());
        assertEquals(List.of("COMPANY_PROFILE"), status.missingRequirements());
    }

    @Test
    void completeAamSetupDoesNotRequireOptionalCompanyFields() {
        when(companyProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(completeProfile()));
        when(catalogItemRepository.findAllByUserId(USER_ID))
                .thenReturn(List.of(catalogItem(USER_ID, "1250", "db")));

        QuoteSetupStatus status = service.getStatus(USER_ID);

        assertTrue(status.companyProfileComplete());
        assertTrue(status.catalogReady());
        assertTrue(status.quoteGenerationEnabled());
        assertEquals(List.of(), status.missingRequirements());
    }

    @Test
    void vatSetupMustBeExplicitAndConsistent() {
        CompanyProfile profile = completeProfile();
        profile.setTaxRegime("VAT_REGISTERED");
        profile.setVatRegistered(true);
        profile.setVatRate(new BigDecimal("27"));
        when(companyProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        when(catalogItemRepository.findAllByUserId(USER_ID))
                .thenReturn(List.of(catalogItem(USER_ID, "1250", "db")));

        assertTrue(service.getStatus(USER_ID).quoteGenerationEnabled());

        profile.setVatRegistered(false);
        QuoteSetupStatus inconsistent = service.getStatus(USER_ID);
        assertFalse(inconsistent.companyProfileComplete());
        assertEquals(List.of("COMPANY_PROFILE"), inconsistent.missingRequirements());

        profile.setVatRegistered(true);
        profile.setVatRate(null);
        assertFalse(service.getStatus(USER_ID).companyProfileComplete());

        profile.setTaxRegime("REVERSE_CHARGE_UNSUPPORTED");
        profile.setVatRate(null);
        assertFalse(service.getStatus(USER_ID).companyProfileComplete());
    }

    @Test
    void zeroPricedOrMalformedCatalogRowsDoNotMakeCatalogReady() {
        CatalogItem zeroPrice = catalogItem(USER_ID, "0", "db");
        CatalogItem invalidUnit = catalogItem(USER_ID, "1250", "unknown");
        CatalogItem invalidConfig = catalogItem(USER_ID, "1250", "db");
        invalidConfig.setCalculationConfigJson("{not-json");
        CatalogItem blankName = catalogItem(USER_ID, "1250", "db");
        blankName.setName(" ");
        when(catalogItemRepository.findAllByUserId(USER_ID))
                .thenReturn(List.of(zeroPrice, invalidUnit, invalidConfig, blankName));

        assertFalse(service.getStatus(USER_ID).catalogReady());
    }

    @Test
    void requireReadyReturnsOnlyValidItemsOwnedByCurrentUser() {
        when(companyProfileRepository.findByUserId(USER_ID))
                .thenReturn(Optional.of(completeProfile()));
        CatalogItem usable = catalogItem(USER_ID, "1250", "db");
        CatalogItem zeroPrice = catalogItem(USER_ID, "0", "db");
        CatalogItem otherUsersItem = catalogItem(99L, "2500", "db");
        User anotherUser = new User();
        anotherUser.setId(99L);
        otherUsersItem.setUser(anotherUser);
        when(catalogItemRepository.findAllByUserId(USER_ID))
                .thenReturn(List.of(usable, zeroPrice, otherUsersItem));

        assertEquals(List.of(usable), service.requireReady(USER_ID));
    }

    @Test
    void readinessUsesOnlyTheAuthenticatedUsersCatalog() {
        User anotherUser = new User();
        anotherUser.setId(99L);
        CatalogItem anotherUsersItem = catalogItem(99L, "1250", "db");
        anotherUsersItem.setUser(anotherUser);
        when(catalogItemRepository.findAllByUserId(USER_ID)).thenReturn(List.of(anotherUsersItem));

        assertFalse(service.getStatus(USER_ID).catalogReady());
        verify(catalogItemRepository).findAllByUserId(USER_ID);
    }

    @Test
    void requireReadyReturnsStructuredMissingRequirements() {
        QuoteSetupIncompleteException exception = assertThrows(
                QuoteSetupIncompleteException.class, () -> service.requireReady(USER_ID));

        assertEquals(List.of("COMPANY_PROFILE", "CATALOG"), exception.getMissingRequirements());
    }

    private CompanyProfile completeProfile() {
        CompanyProfile profile = new CompanyProfile();
        profile.setCompanyName("Minta Kft.");
        profile.setLegalForm("Kft.");
        profile.setTaxNumber("12345678-1-12");
        profile.setCountry("Magyarország");
        profile.setPostalCode("1011");
        profile.setCity("Budapest");
        profile.setStreetName("Fő");
        profile.setStreetType("utca");
        profile.setHouseNumber("1");
        profile.setTaxRegime("AAM");
        profile.setVatRegistered(false);
        profile.setVatRate(null);
        profile.setOfferorName(null);
        profile.setOfferorPhone(null);
        profile.setEuTaxNumber(null);
        profile.setOfferTerms(null);
        return profile;
    }

    private CatalogItem catalogItem(Long userId, String price, String unit) {
        User user = new User();
        user.setId(userId);
        CatalogItem item = new CatalogItem();
        item.setUser(user);
        item.setName("Munkadíj");
        item.setNetSellingUnitPrice(new BigDecimal(price));
        item.setUnit(unit);
        item.setItemType(CatalogItemType.LABOR);
        item.setCalculationConfigJson(null);
        return item;
    }
}
