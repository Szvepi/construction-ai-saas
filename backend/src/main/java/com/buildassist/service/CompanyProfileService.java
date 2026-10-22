package com.buildassist.service;

import com.buildassist.dto.CompanyProfileDtos.CompanyProfileResponse;
import com.buildassist.dto.CompanyProfileDtos.SaveCompanyProfileRequest;
import com.buildassist.model.CompanyProfile;
import com.buildassist.model.User;
import com.buildassist.repository.CompanyProfileRepository;
import com.buildassist.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyProfileService {

    private static final String COUNTRY = "Magyarország";

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
        profile.setTaxNumber(request.taxNumber().trim());
        profile.setEuTaxNumber(normalizeOptional(request.euTaxNumber()));
        profile.setOfferorName(normalizeOptional(request.offerorName()));
        profile.setOfferorPhone(normalizeOptional(request.offerorPhone()));
        profile.setVatRegistered(request.vatRegistered());
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
            profile.getOfferTerms()
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
}
