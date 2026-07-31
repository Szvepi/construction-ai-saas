package com.buildassist.dto;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record OAuth2UserInfo(
        String email,
        String name,
        String picture,
        String id) {
    }
}


