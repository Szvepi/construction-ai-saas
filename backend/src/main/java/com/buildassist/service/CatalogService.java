package com.buildassist.service;

import com.buildassist.calculation.CatalogCalculationConfig;
import com.buildassist.dto.CatalogDtos.CatalogItemResponse;
import com.buildassist.dto.CatalogDtos.CreateCatalogItemRequest;
import com.buildassist.dto.CatalogDtos.UpdateCatalogItemRequest;
import com.buildassist.model.CatalogItem;
import com.buildassist.model.User;
import com.buildassist.repository.CatalogItemRepository;
import com.buildassist.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CatalogService {

    private final CatalogItemRepository catalogItemRepository;

    private final UserRepository userRepository;
    private final CatalogItemValidator catalogItemValidator;
    private final ObjectMapper objectMapper;

    public CatalogService(
            CatalogItemRepository catalogItemRepository,
            UserRepository userRepository,
            CatalogItemValidator catalogItemValidator,
            ObjectMapper objectMapper) {
        this.catalogItemRepository = catalogItemRepository;
        this.userRepository = userRepository;
        this.catalogItemValidator = catalogItemValidator;
        this.objectMapper = objectMapper;
    }

    public List<CatalogItemResponse> findItemsByUserId(Long userId) {
        return catalogItemRepository.findAllByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CatalogItemResponse createItem(Long userId, CreateCatalogItemRequest request) {
        validate(request.name(), request.netSellingUnitPrice(), request.unit(), request.itemType(),
                request.netProcurementUnitCost(), request.estimatedOwnLaborUnitCost(), request.calculationConfig());
        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty()) {
            throw new IllegalArgumentException("User not found");
        }

        CatalogItem item = new CatalogItem(
                user.get(),
                request.name(),
                request.netSellingUnitPrice(),
                request.unit()
        );
        setCatalogFields(item, request.itemType(), request.netProcurementUnitCost(),
                request.estimatedOwnLaborUnitCost(), request.calculationConfig());

        CatalogItem saved = catalogItemRepository.save(item);
        return toResponse(saved);
    }

    @Transactional
    public CatalogItemResponse updateItem(Long userId, Long itemId, UpdateCatalogItemRequest request) {
        validate(request.name(), request.netSellingUnitPrice(), request.unit(), request.itemType(),
                request.netProcurementUnitCost(), request.estimatedOwnLaborUnitCost(), request.calculationConfig());
        CatalogItem item = catalogItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Catalog item not found"));

        if (!item.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to update this item");
        }

        item.setName(request.name());
        item.setNetSellingUnitPrice(request.netSellingUnitPrice());
        item.setUnit(request.unit());
        setCatalogFields(item, request.itemType(), request.netProcurementUnitCost(),
                request.estimatedOwnLaborUnitCost(), request.calculationConfig());

        CatalogItem updated = catalogItemRepository.save(item);
        return toResponse(updated);
    }

    @Transactional
    public void deleteItem(Long userId, Long itemId) {
        CatalogItem item = catalogItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Catalog item not found"));

        if (!item.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to delete this item");
        }

        catalogItemRepository.delete(item);
    }

    private CatalogItemResponse toResponse(CatalogItem item) {
        return new CatalogItemResponse(
                item.getId(),
                item.getName(),
                item.getNetSellingUnitPrice(),
                item.getUnit(),
                item.getItemType(),
                item.getNetProcurementUnitCost(),
                item.getEstimatedOwnLaborUnitCost(),
                readConfig(item.getCalculationConfigJson()),
                item.getCreatedAt()
        );
    }

    private void validate(
            String name,
            java.math.BigDecimal sellingPrice,
            String unit,
            com.buildassist.calculation.CatalogItemType itemType,
            java.math.BigDecimal procurementCost,
            java.math.BigDecimal laborCost,
            CatalogCalculationConfig calculationConfig) {
        catalogItemValidator.validate(name, sellingPrice, unit, itemType,
                procurementCost, laborCost, calculationConfig);
    }

    private void setCatalogFields(
            CatalogItem item,
            com.buildassist.calculation.CatalogItemType itemType,
            java.math.BigDecimal procurementCost,
            java.math.BigDecimal laborCost,
            CatalogCalculationConfig calculationConfig) {
        item.setItemType(itemType);
        item.setNetProcurementUnitCost(procurementCost);
        item.setEstimatedOwnLaborUnitCost(laborCost);
        item.setCalculationConfigJson(writeConfig(calculationConfig));
    }

    private String writeConfig(CatalogCalculationConfig config) {
        if (config == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("The catalog calculation configuration could not be serialized.",
                    exception);
        }
    }

    private CatalogCalculationConfig readConfig(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CatalogCalculationConfig.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The stored catalog calculation configuration is invalid.", exception);
        }
    }
}
