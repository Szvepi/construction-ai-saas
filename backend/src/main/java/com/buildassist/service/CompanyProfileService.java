package com.buildassist.service;

import com.buildassist.dto.CompanyProfileDtos.CompanyProfileResponse;
import com.buildassist.dto.CompanyProfileDtos.SaveCompanyProfileRequest;
import com.buildassist.model.CompanyProfile;
import com.buildassist.model.User;
import com.buildassist.repository.CompanyProfileRepository;
import com.buildassist.repository.UserRepository;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyProfileService {

    private static final String COUNTRY = "Magyarország";
    private static final int[] TAX_NUMBER_CHECK_WEIGHTS = {9, 7, 3, 1, 9, 7, 3};
    private static final Set<String> VAT_CODES = Set.of("1", "2", "3", "4", "5");
    private static final Set<String> TAX_AUTHORITY_CODES = Set.of(
            "02", "03", "04", "05", "06", "07", "08", "09", "10",
            "11", "12", "13", "14", "15", "16", "17", "18", "19", "20",
            "22", "23", "24", "25", "26", "27", "28", "29", "30",
            "31", "32", "33", "34", "35", "36", "37", "38", "39", "40",
            "41", "42", "43", "44", "51");

    private final CompanyProfileRepository companyProfileRepository;
    private final UserRepository userRepository;

    public CompanyProfileService(
            CompanyProfileRepository companyProfileRepository,
            UserRepository userRepository) {
        this.companyProfileRepository = companyProfileRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public CompanyProfileResponse getProfile(Long userId) {
        return companyProfileRepository.findByUserId(userId)
            .map(this::toResponse)
            .orElseGet(this::emptyResponse);
    }

    @Transactional
    public CompanyProfileResponse saveProfile(Long userId, SaveCompanyProfileRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("A céges adatok megadása kötelező.");
        }
        String taxNumber = normalizeTaxNumber(request.taxNumber());
        String taxRegime = request.taxRegime() == null || request.taxRegime().isBlank()
                ? (request.vatRegistered() ? "VAT_REGISTERED" : "AAM")
                : request.taxRegime().trim();
        if (!Set.of("AAM", "VAT_REGISTERED", "REVERSE_CHARGE_UNSUPPORTED").contains(taxRegime)) {
            throw new IllegalArgumentException("Nem támogatott adózási mód.");
        }
        BigDecimal vatRate = request.vatRate();
        if ("VAT_REGISTERED".equals(taxRegime)) {
            if (vatRate == null || vatRate.signum() <= 0 || vatRate.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("ÁFA-körös vállalkozásnál 0 és 100 közötti ÁFA-kulcs szükséges.");
            }
        } else if (vatRate != null) {
            throw new IllegalArgumentException("ÁFA-kulcs csak ÁFA-körös adózási módnál adható meg.");
        }
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        CompanyProfile profile = companyProfileRepository.findByUserId(userId)
            .orElseGet(() -> {
                CompanyProfile newProfile = new CompanyProfile();
                newProfile.setUser(user);
                return newProfile;
            });

        profile.setCompanyName(request.companyName().trim());
        profile.setLegalForm(request.legalForm().trim());
        profile.setTaxNumber(taxNumber);
        profile.setEuTaxNumber(normalizeOptional(request.euTaxNumber()));
        profile.setOfferorName(normalizeOptional(request.offerorName()));
        profile.setOfferorPhone(PhoneNumberUtils.normalizeOptionalHungarianPhoneNumber(request.offerorPhone()));
        profile.setTaxRegime(taxRegime);
        profile.setVatRegistered("VAT_REGISTERED".equals(taxRegime));
        profile.setVatRate(vatRate);
        profile.setOfferTerms(request.offerTerms() != null ? normalizeOptional(request.offerTerms()) : CompanyProfile.DEFAULT_OFFER_TERMS);
        profile.setCountry(COUNTRY);
        profile.setPostalCode(request.postalCode().trim());
        profile.setCity(request.city().trim());
        profile.setStreetName(request.streetName().trim());
        profile.setStreetType(request.streetType().trim());
        profile.setHouseNumber(request.houseNumber().trim());
        profile.setBuilding(normalizeOptional(request.building()));
        profile.setStaircase(normalizeOptional(request.staircase()));
        profile.setFloor(normalizeOptional(request.floor()));
        profile.setDoor(normalizeOptional(request.door()));

        return toResponse(companyProfileRepository.save(profile));
    }

    private CompanyProfileResponse toResponse(CompanyProfile profile) {
        return new CompanyProfileResponse(
            profile.getCompanyName(),
            profile.getLegalForm(),
            profile.getTaxNumber(),
            profile.getEuTaxNumber(),
            profile.getOfferorName(),
            profile.getOfferorPhone(),
            COUNTRY,
            profile.getPostalCode(),
            profile.getCity(),
            profile.getStreetName(),
            profile.getStreetType(),
            profile.getHouseNumber(),
            profile.getBuilding(),
            profile.getStaircase(),
            profile.getFloor(),
            profile.getDoor(),
            profile.isVatRegistered(),
            profile.getOfferTerms(),
            profile.getTaxRegime(),
            profile.getVatRate()
        );
    }

    private CompanyProfileResponse emptyResponse() {
        return new CompanyProfileResponse(
            "",
            "",
            "",
            null,
            null,
            null,
            COUNTRY,
            "",
            "",
            "",
            "",
            "",
            null,
            null,
            null,
            null,
            false,
            CompanyProfile.DEFAULT_OFFER_TERMS
        );
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeTaxNumber(String value) {
        if (value == null || !value.trim().matches("\\d{8}(?:[-\\s]?\\d)(?:[-\\s]?\\d{2})")) {
            throw invalidTaxNumber();
        }
        String digits = value.replaceAll("[-\\s]", "");
        int weightedSum = 0;
        for (int i = 0; i < TAX_NUMBER_CHECK_WEIGHTS.length; i++) {
            weightedSum += (digits.charAt(i) - '0') * TAX_NUMBER_CHECK_WEIGHTS[i];
        }
        int expectedCheckDigit = (10 - weightedSum % 10) % 10;
        String vatCode = digits.substring(8, 9);
        String taxAuthorityCode = digits.substring(9);
        if (digits.charAt(7) - '0' != expectedCheckDigit
                || !VAT_CODES.contains(vatCode)
                || !TAX_AUTHORITY_CODES.contains(taxAuthorityCode)
                || ("5".equals(vatCode) && !digits.startsWith("177"))) {
            throw invalidTaxNumber();
        }
        return digits.substring(0, 8) + "-" + digits.charAt(8) + "-" + digits.substring(9);
    }

    private IllegalArgumentException invalidTaxNumber() {
        return new IllegalArgumentException(
                "Az adószám nem érvényes magyar adószám. Ellenőrizd a törzsszámot, áfakódot és területi kódot.");
    }
}
