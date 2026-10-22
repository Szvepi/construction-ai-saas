package com.buildassist.controller;

import com.buildassist.dto.CompanyProfileDtos.CompanyProfileResponse;
import com.buildassist.dto.CompanyProfileDtos.SaveCompanyProfileRequest;
import com.buildassist.security.SecurityUtils;
import com.buildassist.service.CompanyProfileService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Company settings", description = "Manage the authenticated user's company profile")
@RestController
@RequestMapping("/api/company-settings")
public class CompanyProfileController {

    private final CompanyProfileService companyProfileService;

    public CompanyProfileController(CompanyProfileService companyProfileService) {
        this.companyProfileService = companyProfileService;
    }

    @GetMapping
    public CompanyProfileResponse getProfile() {
        return companyProfileService.getProfile(SecurityUtils.currentUserId());
    }

    @PutMapping
    public CompanyProfileResponse saveProfile(@Valid @RequestBody SaveCompanyProfileRequest request) {
        return companyProfileService.saveProfile(SecurityUtils.currentUserId(), request);
    }
}
