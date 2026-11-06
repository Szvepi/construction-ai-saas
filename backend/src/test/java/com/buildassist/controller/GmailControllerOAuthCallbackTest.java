package com.buildassist.controller;

import com.buildassist.config.AppProperties;
import com.buildassist.security.FrontendRedirectService;
import com.buildassist.service.GmailService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailControllerOAuthCallbackTest {

    @Test
    void successfulGmailCallbackRedirectsToFrontendDashboard() throws Exception {
        GmailService gmailService = mock(GmailService.class);
        GmailController controller = controller(gmailService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.callback("oauth-code", "signed-state", null, response);

        verify(gmailService).handleOAuthCallback("oauth-code", "signed-state");
        assertEquals("http://localhost:3000/dashboard?gmailConnect=connected", response.getRedirectedUrl());
    }

    @Test
    void deniedGmailCallbackKeepsMiraLoginAndReturnsSafeFrontendError() throws Exception {
        GmailService gmailService = mock(GmailService.class);
        when(gmailService.validateOAuthState("signed-state")).thenReturn(42L);
        GmailController controller = controller(gmailService);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.callback(null, "signed-state", "access_denied", response);

        verify(gmailService).validateOAuthState("signed-state");
        assertEquals("http://localhost:3000/dashboard?gmailConnect=failed", response.getRedirectedUrl());
    }

    private static GmailController controller(GmailService service) {
        AppProperties properties = new AppProperties();
        properties.setFrontendUrl("http://localhost:3000");
        properties.getCors().setAllowedOrigins("http://localhost:3000");
        return new GmailController(service, new FrontendRedirectService(properties));
    }
}
