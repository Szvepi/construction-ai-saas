package com.buildassist.service;

import com.buildassist.dto.GmailDtos.GmailConnectResponse;
import com.buildassist.dto.GmailDtos.GmailStatusResponse;
import org.springframework.stereotype.Service;

@Service
public class GmailService {

    public GmailConnectResponse getAuthorizationUrl(Long userId) {
        throw new UnsupportedOperationException("Gmail OAuth not implemented yet");
    }

    public void handleOAuthCallback(String code, String state) {
        throw new UnsupportedOperationException("Gmail OAuth callback not implemented yet");
    }

    public GmailStatusResponse getConnectionStatus(Long userId) {
        throw new UnsupportedOperationException("Gmail status not implemented yet");
    }
}
