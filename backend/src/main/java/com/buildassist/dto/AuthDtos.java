package com.buildassist.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password) {
    }

    public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {
    }

    public record AuthResponse(String token, String email) {
    }

    public record OAuth2UserInfo(
        String email,
        String name,
        String picture,
        String id) {
    }
}

