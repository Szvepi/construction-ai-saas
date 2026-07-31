package com.buildassist.controller;

import com.buildassist.dto.AuthDtos.OAuth2UserInfo;
import com.buildassist.security.SecurityUtils;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Authentication")
@SecurityRequirements
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/user")
    public OAuth2UserInfo getCurrentUser(Authentication authentication) {
        if (authentication == null) {
            throw new IllegalStateException("No authenticated user");
        }

        // Handle OAuth2 principal
        if (authentication.getPrincipal() instanceof OAuth2User oauth2User) {
            String email = oauth2User.getAttribute("email");
            String name = oauth2User.getAttribute("name");
            String picture = oauth2User.getAttribute("picture");
            String id = oauth2User.getAttribute("sub");
            return new OAuth2UserInfo(email, name, picture, id);
        }

        // Handle JWT principal (userId as Long)
        if (authentication.getPrincipal() instanceof Long userId) {
            // For JWT auth, we only have the userId, not full user info
            // Frontend should handle this case
            return new OAuth2UserInfo(null, null, null, userId.toString());
        }

        throw new IllegalStateException("Unknown authentication principal type");
    }
}


