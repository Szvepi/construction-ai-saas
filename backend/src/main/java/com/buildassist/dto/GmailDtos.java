package com.buildassist.dto;

public final class GmailDtos {

    private GmailDtos() {
    }

    public record GmailConnectResponse(String authorizationUrl) {
    }

    public record GmailStatusResponse(boolean connected, String gmailAddress) {
    }
}
