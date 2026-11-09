package com.buildassist.service;

import com.buildassist.calculation.CatalogCalculationConfig;
import com.buildassist.calculation.CatalogCalculationConfigValidator;
import com.buildassist.calculation.QuantityUnit;
import com.buildassist.dto.QuoteSetupDtos.QuoteSetupStatus;
import com.buildassist.model.CatalogItem;
import com.buildassist.model.CompanyProfile;
import com.buildassist.repository.CatalogItemRepository;
import com.buildassist.repository.CompanyProfileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteSetupValidationService {

    private static final BigDecimal MAX_CATALOG_PRICE = new BigDecimal("99999999.99");
    private static final BigDecimal MAX_VAT_RATE = new BigDecimal("100");

    private final CompanyProfileRepository companyProfileRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final ObjectMapper objectMapper;
    private final CatalogCalculationConfigValidator configValidator =
            new CatalogCalculationConfigValidator();

    public QuoteSetupValidationService(
            CompanyProfileRepository companyProfileRepository,
            CatalogItemRepository catalogItemRepository,
            ObjectMapper objectMapper) {
        this.companyProfileRepository = companyProfileRepository;
        this.catalogItemRepository = catalogItemRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public QuoteSetupStatus getStatus(Long userId) {
        return evaluate(userId).status();
    }

    @Transactional(readOnly = true)
    public List<CatalogItem> requireReady(Long userId) {
        Readiness readiness = evaluate(userId);
        if (!readiness.status().quoteGenerationEnabled()) {
            throw new QuoteSetupIncompleteException(readiness.status().missingRequirements());
        }
        return readiness.catalogItems();
    }

    private Readiness evaluate(Long userId) {
        boolean companyComplete = companyProfileRepository.findByUserId(userId)
                .filter(this::isCompanyProfileComplete)
                .isPresent();
        List<CatalogItem> catalogItems = getUsableCatalogItems(userId);
        List<String> missing = new ArrayList<>();
        if (!companyComplete) {
            missing.add("COMPANY_PROFILE");
        }
        if (catalogItems.isEmpty()) {
            missing.add("CATALOG");
        }
        return new Readiness(
                new QuoteSetupStatus(
                        companyComplete, !catalogItems.isEmpty(), missing.isEmpty(), List.copyOf(missing)),
                catalogItems);
    }

    public boolean isUsableCatalogItem(Long userId, CatalogItem item) {
        return belongsToUser(item, userId) && isUsableCatalogItem(item);
    }

    private List<CatalogItem> getUsableCatalogItems(Long userId) {
        return catalogItemRepository.findAllByUserId(userId).stream()
                .filter(item -> isUsableCatalogItem(userId, item))
                .toList();
    }

    private boolean isCompanyProfileComplete(CompanyProfile profile) {
        return hasText(profile.getCompanyName())
                && hasText(profile.getLegalForm())
                && hasText(profile.getTaxNumber())
                && hasText(profile.getCountry())
                && hasText(profile.getPostalCode())
                && hasText(profile.getCity())
                && hasText(profile.getStreetName())
                && hasText(profile.getStreetType())
                && hasText(profile.getHouseNumber())
                && hasValidTaxConfiguration(profile);
    }

    private boolean hasValidTaxConfiguration(CompanyProfile profile) {
        String regime = profile.getTaxRegime();
        BigDecimal rate = profile.getVatRate();
        if ("AAM".equals(regime)) {
            return !profile.isVatRegistered() && rate == null;
        }
        if ("VAT_REGISTERED".equals(regime)) {
            return profile.isVatRegistered()
                    && rate != null
                    && rate.signum() > 0
                    && rate.compareTo(MAX_VAT_RATE) <= 0;
        }
        return false;
    }

    private boolean isUsableCatalogItem(CatalogItem item) {
        if (item == null || !hasText(item.getName()) || item.getItemType() == null
                || !isUsablePrice(item.getNetSellingUnitPrice())) {
            return false;
        }
        try {
            QuantityUnit.fromCode(item.getUnit());
            return hasValidCalculationConfig(item.getCalculationConfigJson());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean belongsToUser(CatalogItem item, Long userId) {
        return item != null && item.getUser() != null
                && userId != null && userId.equals(item.getUser().getId());
    }

    private boolean isUsablePrice(BigDecimal price) {
        return price != null
                && price.signum() > 0
                && price.scale() <= 2
                && price.compareTo(MAX_CATALOG_PRICE) <= 0;
    }

    private boolean hasValidCalculationConfig(String json) {
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            CatalogCalculationConfig config =
                    objectMapper.readValue(json, CatalogCalculationConfig.class);
            return configValidator.validate(config).isEmpty();
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Readiness(QuoteSetupStatus status, List<CatalogItem> catalogItems) {
    }
}
