package com.buildassist.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

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
        String offerTerms,
        String taxRegime,
        BigDecimal vatRate) {
        public SaveCompanyProfileRequest(
                String companyName, String legalForm, String taxNumber, String euTaxNumber,
                String offerorName, String offerorPhone, String postalCode, String city,
                String streetName, String streetType, String houseNumber, String building,
                String staircase, String floor, String door, boolean vatRegistered, String offerTerms) {
            this(companyName, legalForm, taxNumber, euTaxNumber, offerorName, offerorPhone,
                    postalCode, city, streetName, streetType, houseNumber, building, staircase,
                    floor, door, vatRegistered, offerTerms, null, null);
        }
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
        String offerTerms,
        String taxRegime,
        BigDecimal vatRate) {
        public CompanyProfileResponse(
                String companyName, String legalForm, String taxNumber, String euTaxNumber,
                String offerorName, String offerorPhone, String country, String postalCode,
                String city, String streetName, String streetType, String houseNumber,
                String building, String staircase, String floor, String door,
                boolean vatRegistered, String offerTerms) {
            this(companyName, legalForm, taxNumber, euTaxNumber, offerorName, offerorPhone,
                    country, postalCode, city, streetName, streetType, houseNumber, building,
                    staircase, floor, door, vatRegistered, offerTerms,
                    vatRegistered ? "VAT_REGISTERED" : "AAM", null);
        }
    }
}
