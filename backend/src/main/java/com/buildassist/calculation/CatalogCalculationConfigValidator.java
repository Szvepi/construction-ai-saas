package com.buildassist.calculation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class CatalogCalculationConfigValidator {

    private static final int MAX_PARAMETERS = 32;
    private static final Pattern PARAMETER_ID = Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,79}");

    public List<String> validate(CatalogCalculationConfig config) {
        if (config == null) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        if (config.allowedCalculations().size() > CalculationOperation.values().length) {
            errors.add("Too many allowed calculations.");
        }
        if (config.defaultParameters().size() > MAX_PARAMETERS
                || config.parameterConstraints().size() > MAX_PARAMETERS) {
            errors.add("A catalog configuration may define at most 32 parameters.");
        }
        if (config.materialAllowancePercent() != null
                && (config.materialAllowancePercent().signum() < 0
                || config.materialAllowancePercent().compareTo(new BigDecimal("100")) > 0)) {
            errors.add("Material allowance must be between 0 and 100 percent.");
        }
        if (config.roundingPolicy() != null) {
            if (config.roundingPolicy().scale() < 0 || config.roundingPolicy().scale() > 6) {
                errors.add("Quantity rounding scale must be between 0 and 6.");
            }
            if (config.roundingPolicy().mode() == null) {
                errors.add("A rounding mode is required when a rounding policy is configured.");
            }
        }

        validateParameterNames(config.defaultParameters(), "default", errors);
        validateParameterNames(config.parameterConstraints(), "constraint", errors);
        config.defaultParameters().forEach((name, value) -> validateDefault(name, value, config, errors));
        config.parameterConstraints().forEach((name, constraint) ->
                validateConstraint(name, constraint, errors));
        return List.copyOf(errors);
    }

    private void validateParameterNames(Map<String, ?> values, String kind, List<String> errors) {
        values.keySet().stream()
                .filter(name -> name == null || !PARAMETER_ID.matcher(name).matches())
                .forEach(name -> errors.add("Invalid " + kind + " parameter name: " + name));
    }

    private void validateDefault(
            String name, CatalogParameterDefault value, CatalogCalculationConfig config, List<String> errors) {
        if (value == null || value.value() == null || value.source() == null) {
            errors.add("Default parameter " + name + " requires a value and source.");
            return;
        }
        validateUnit(value.unit(), "Default parameter " + name, errors);
        if (value.source() != InputSource.CATALOG_DEFAULT && value.source() != InputSource.CONTRACTOR_PROFILE) {
            errors.add("Default parameter " + name + " must come from the catalog or contractor profile.");
        }
        if (value.value().signum() < 0) {
            errors.add("Default parameter " + name + " cannot be negative.");
        }
        validateQuantity(value.value(), value.unit(), "Default parameter " + name, errors);
        CatalogParameterConstraint constraint = config.parameterConstraints().get(name);
        if (constraint != null) {
            if (!Objects.equals(value.unit(), constraint.unit())) {
                errors.add("Default and constraint units must match for parameter " + name + ".");
            }
            if (constraint.minimum() != null && value.value().compareTo(constraint.minimum()) < 0) {
                errors.add("Default parameter " + name + " is below its minimum.");
            }
            if (constraint.maximum() != null && value.value().compareTo(constraint.maximum()) > 0) {
                errors.add("Default parameter " + name + " exceeds its maximum.");
            }
        }
    }

    private void validateConstraint(
            String name, CatalogParameterConstraint constraint, List<String> errors) {
        if (constraint == null) {
            errors.add("Parameter constraint " + name + " cannot be null.");
            return;
        }
        validateUnit(constraint.unit(), "Parameter constraint " + name, errors);
        if (constraint.minimum() != null && constraint.minimum().signum() < 0) {
            errors.add("Parameter constraint " + name + " cannot have a negative minimum.");
        }
        if (constraint.maximum() != null && constraint.maximum().signum() < 0) {
            errors.add("Parameter constraint " + name + " cannot have a negative maximum.");
        }
        if (constraint.minimum() != null && constraint.maximum() != null
                && constraint.minimum().compareTo(constraint.maximum()) > 0) {
            errors.add("Parameter constraint " + name + " has a minimum greater than its maximum.");
        }
        if (constraint.minimum() != null) {
            validateQuantity(constraint.minimum(), constraint.unit(),
                    "Parameter constraint " + name + " minimum", errors);
        }
        if (constraint.maximum() != null) {
            validateQuantity(constraint.maximum(), constraint.unit(),
                    "Parameter constraint " + name + " maximum", errors);
        }
    }

    private void validateUnit(String unit, String field, List<String> errors) {
        try {
            QuantityUnit.fromCode(unit);
        } catch (IllegalArgumentException exception) {
            errors.add(field + " has an unknown unit.");
        }
    }

    private void validateQuantity(
            BigDecimal value, String unit, String field, List<String> errors) {
        QuantityUnit quantityUnit;
        try {
            quantityUnit = QuantityUnit.fromCode(unit);
        } catch (IllegalArgumentException exception) {
            return;
        }
        try {
            new Quantity(value, quantityUnit);
        } catch (IllegalArgumentException exception) {
            errors.add(field + " exceeds the supported numeric range.");
        }
    }
}
