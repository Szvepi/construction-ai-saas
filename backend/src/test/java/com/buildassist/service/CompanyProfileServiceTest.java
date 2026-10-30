package com.buildassist.service;

import com.buildassist.dto.CompanyProfileDtos.CompanyProfileResponse;
import com.buildassist.dto.CompanyProfileDtos.SaveCompanyProfileRequest;
import com.buildassist.model.CompanyProfile;
import com.buildassist.model.User;
import com.buildassist.repository.CompanyProfileRepository;
import com.buildassist.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyProfileServiceTest {

    @Mock
    private CompanyProfileRepository companyProfileRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CompanyProfileService companyProfileService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(7L);
    }

    @Test
    void getProfileReturnsFixedCountryAndBlankFieldsWhenNotYetSaved() {
        when(companyProfileRepository.findByUserId(7L)).thenReturn(Optional.empty());

        CompanyProfileResponse response = companyProfileService.getProfile(7L);

        assertEquals("", response.companyName());
        assertEquals("Magyarország", response.country());
        assertNull(response.euTaxNumber());
        assertNull(response.offerorName());
        assertNull(response.offerorPhone());
    }

    @Test
    void saveProfileCreatesUserScopedProfileAndNormalizesOptionalFields() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(companyProfileRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(companyProfileRepository.save(any(CompanyProfile.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        CompanyProfileResponse response = companyProfileService.saveProfile(7L, request("   "));

        assertEquals("Minta Kft.", response.companyName());
        assertEquals("Magyarország", response.country());
        assertNull(response.euTaxNumber());
        assertEquals("Kovács János", response.offerorName());
        assertEquals("+36 30 123 4567", response.offerorPhone());
        verify(companyProfileRepository).save(any(CompanyProfile.class));
    }

    @Test
    void saveProfileUpdatesExistingProfileInsteadOfCreatingAnother() {
        CompanyProfile profile = new CompanyProfile();
        profile.setUser(user);
        profile.setCompanyName("Régi cégnév");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(companyProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile));
        when(companyProfileRepository.save(profile)).thenReturn(profile);

        CompanyProfileResponse response = companyProfileService.saveProfile(7L, request(null));

        assertEquals("Minta Kft.", response.companyName());
        verify(companyProfileRepository).save(profile);
    }

    @Test
    void rejectsVatRegisteredProfileWithoutExplicitRate() {
        SaveCompanyProfileRequest base = request(null);
        SaveCompanyProfileRequest vat = new SaveCompanyProfileRequest(
                base.companyName(), base.legalForm(), base.taxNumber(), base.euTaxNumber(),
                base.offerorName(), base.offerorPhone(), base.postalCode(), base.city(),
                base.streetName(), base.streetType(), base.houseNumber(), base.building(),
                base.staircase(), base.floor(), base.door(), true, base.offerTerms(),
                "VAT_REGISTERED", null);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> companyProfileService.saveProfile(7L, vat));
    }

    @Test
    void persistsExplicitVatRateWithoutHardcodingStandardRate() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(companyProfileRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(companyProfileRepository.save(any(CompanyProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SaveCompanyProfileRequest base = request(null);
        SaveCompanyProfileRequest vat = new SaveCompanyProfileRequest(
                base.companyName(), base.legalForm(), base.taxNumber(), base.euTaxNumber(),
                base.offerorName(), base.offerorPhone(), base.postalCode(), base.city(),
                base.streetName(), base.streetType(), base.houseNumber(), base.building(),
                base.staircase(), base.floor(), base.door(), true, base.offerTerms(),
                "VAT_REGISTERED", new BigDecimal("18"));

        CompanyProfileResponse response = companyProfileService.saveProfile(7L, vat);

        assertEquals("VAT_REGISTERED", response.taxRegime());
        assertEquals(new BigDecimal("18"), response.vatRate());
    }

    private SaveCompanyProfileRequest request(String euTaxNumber) {
        return new SaveCompanyProfileRequest(
            " Minta Kft. ",
            " Kft. ",
            " 12345678-1-42 ",
            euTaxNumber,
            " Kovács János ",
            " +36 30 123 4567 ",
            " 1011 ",
            " Budapest ",
            " Fő ",
            " utca ",
            " 1 ",
            null,
            null,
            null,
            null,
            false,
            null
        );
    }
}
