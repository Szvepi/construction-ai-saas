package com.buildassist.service;

import com.buildassist.config.AppProperties;
import com.buildassist.dto.GmailDtos.GmailConnectResponse;
import com.buildassist.dto.GmailDtos.GmailStatusResponse;
import com.buildassist.model.GmailConnection;
import com.buildassist.model.User;
import com.buildassist.repository.GmailConnectionRepository;
import com.buildassist.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

@Service
public class GmailService {

    private final AppProperties appProperties;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final UserRepository userRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GmailService(
            AppProperties appProperties,
            GmailConnectionRepository gmailConnectionRepository,
            UserRepository userRepository,
            TokenEncryptionService tokenEncryptionService) {
        this.appProperties = appProperties;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.userRepository = userRepository;
        this.tokenEncryptionService = tokenEncryptionService;
    }

    public GmailConnectResponse getAuthorizationUrl(Long userId) {
        String clientId = appProperties.getGmail().getClientId();
        String redirectUri = appProperties.getGmail().getRedirectUri();
        // request Gmail send + readonly and basic profile info
        String scope = String.join(" ",
                "openid",
                "email",
                "profile",
                "https://www.googleapis.com/auth/gmail.send",
                "https://www.googleapis.com/auth/gmail.readonly"
        );

        String url = "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_type=code"
                + "&scope=" + urlEncode(scope)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + urlEncode(String.valueOf(userId));

        return new GmailConnectResponse(url);
    }

    public void handleOAuthCallback(String code, String state) {
        try {
            Long userId = Long.parseLong(state);

            String tokenEndpoint = "https://oauth2.googleapis.com/token";
            String clientId = appProperties.getGmail().getClientId();
            String clientSecret = appProperties.getGmail().getClientSecret();
            String redirectUri = appProperties.getGmail().getRedirectUri();

            String form = "code=" + urlEncode(code)
                    + "&client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&grant_type=authorization_code";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("Token endpoint returned " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode tokenJson = objectMapper.readTree(resp.body());
            String accessToken = tokenJson.path("access_token").asText(null);
            String refreshToken = tokenJson.path("refresh_token").asText(null);
            long expiresIn = tokenJson.path("expires_in").asLong(0L);

            if (accessToken == null) {
                throw new RuntimeException("No access_token in token response");
            }

            // fetch user info (email)
            HttpRequest userReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.googleapis.com/oauth2/v1/userinfo?alt=json"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> userResp = httpClient.send(userReq, HttpResponse.BodyHandlers.ofString());
            if (userResp.statusCode() >= 400) {
                throw new RuntimeException("Userinfo endpoint returned " + userResp.statusCode() + ": " + userResp.body());
            }

            JsonNode userJson = objectMapper.readTree(userResp.body());
            String email = userJson.path("email").asText(null);
            if (email == null) {
                throw new RuntimeException("Failed to obtain gmail address from userinfo");
            }

            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                throw new IllegalStateException("User not found: " + userId);
            }
            User user = userOpt.get();

            Optional<GmailConnection> existing = gmailConnectionRepository.findByUserId(userId);
            GmailConnection conn = existing.orElseGet(GmailConnection::new);
            conn.setUser(user);
            conn.setGmailAddress(email);
            conn.setAccessTokenEncrypted(tokenEncryptionService.encrypt(accessToken));
            if (refreshToken != null && !refreshToken.isBlank()) {
                conn.setRefreshTokenEncrypted(tokenEncryptionService.encrypt(refreshToken));
            }
            if (expiresIn > 0) {
                conn.setTokenExpiresAt(Instant.now().plusSeconds(expiresIn));
            } else {
                conn.setTokenExpiresAt(Instant.now().plusSeconds(3600));
            }

            gmailConnectionRepository.save(conn);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to handle OAuth callback", ex);
        }
    }

    public GmailStatusResponse getConnectionStatus(Long userId) {
        Optional<GmailConnection> optionalGmailConnection = gmailConnectionRepository.findByUserId(userId);
        if (optionalGmailConnection.isEmpty()) {
            return new GmailStatusResponse(false, null);
        }
        GmailConnection gmailConnection = optionalGmailConnection.get();
        return new GmailStatusResponse(true, gmailConnection.getGmailAddress());
    }

    private static String urlEncode(String url) {
        return URLEncoder.encode(url == null ? "" : url, StandardCharsets.UTF_8);
    }
}
