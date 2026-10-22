package com.buildassist.dto;

import jakarta.validation.constraints.NotBlank;

public final class CompanyProfileDtos {

    private CompanyProfileDtos() {
    }

    public record SaveCompanyProfileRequest(
        @NotBlank String companyName,
        @NotBlank String legalForm,
        @NotBlank String taxNumber,
        String euTaxNumber,
        String offerorName,
        String offerorPhone,
        @NotBlank String postalCode,
        @NotBlank String city,
        @NotBlank String streetName,
        @NotBlank String streetType,
        @NotBlank String houseNumber,
        String building,
        String staircase,
        String floor,
        String door,
        boolean vatRegistered,
        String offerTerms) {
    }

    public record CompanyProfileResponse(
        String companyName,
        String legalForm,
        String taxNumber,
        String euTaxNumber,
        String offerorName,
        String offerorPhone,
        String country,
        String postalCode,
        String city,
        String streetName,
        String streetType,
        String houseNumber,
        String building,
        String staircase,
        String floor,
        String door,
        boolean vatRegistered,
        String offerTerms) {
    }
}
