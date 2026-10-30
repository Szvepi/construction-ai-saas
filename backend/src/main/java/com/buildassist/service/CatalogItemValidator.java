package com.buildassist.service;

import com.buildassist.calculation.CatalogCalculationConfig;
import com.buildassist.calculation.CatalogCalculationConfigValidator;
import com.buildassist.calculation.CatalogItemType;
import com.buildassist.calculation.QuantityUnit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CatalogItemValidator {

    private final CatalogCalculationConfigValidator configValidator = new CatalogCalculationConfigValidator();

    public void validate(
            String name,
            BigDecimal netSellingUnitPrice,
            String unit,
            CatalogItemType itemType,
            BigDecimal netProcurementUnitCost,
            BigDecimal estimatedOwnLaborUnitCost,
            CatalogCalculationConfig calculationConfig) {
        List<String> errors = new ArrayList<>();
        if (name == null || name.isBlank() || name.length() > 255) {
            errors.add("A catalog item name is required and must be at most 255 characters.");
        }
        validateMoney(netSellingUnitPrice, "Net selling unit price", 10, errors, true);
        if (unit == null || unit.isBlank() || unit.length() > 50) {
            errors.add("A catalog unit is required and must be at most 50 characters.");
        } else {
            try {
                QuantityUnit.fromCode(unit);
            } catch (IllegalArgumentException exception) {
                errors.add("The catalog item unit is not supported.");
            }
        }
        if (itemType == null) {
            errors.add("A catalog item type is required.");
        } else {
            validateCosts(itemType, netProcurementUnitCost, estimatedOwnLaborUnitCost, errors);
        }
        errors.addAll(configValidator.validate(calculationConfig));
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", errors));
        }
    }

    private void validateCosts(
            CatalogItemType itemType,
            BigDecimal procurementCost,
            BigDecimal laborCost,
            List<String> errors) {
        validateMoney(procurementCost, "Net procurement unit cost", 12, errors, false);
        validateMoney(laborCost, "Estimated own labor unit cost", 12, errors, false);
        if ((itemType == CatalogItemType.LABOR || itemType == CatalogItemType.OTHER)
                && procurementCost != null) {
            errors.add("This item type cannot have a material procurement cost.");
        }
        if ((itemType == CatalogItemType.MATERIAL || itemType == CatalogItemType.OTHER)
                && laborCost != null) {
            errors.add("This item type cannot have an estimated own labor cost.");
        }
    }

    private void validateMoney(
            BigDecimal value, String field, int maximumPrecision, List<String> errors, boolean required) {
        if (value == null) {
            if (required) {
                errors.add(field + " is required.");
            }
            return;
        }
        if (value.signum() < 0 || value.scale() > 2 || value.precision() > maximumPrecision) {
            errors.add(field + " must be non-negative and fit its catalog numeric column.");
        }
    }
}
