# Google OAuth2 Setup Guide

This guide explains the Google OAuth2 login integration with Spring Security and Gmail API access.

## Overview

The application now uses **Spring Security OAuth2 Client** for Google login, replacing the previous manual OAuth2 flow. This provides:

- Automatic Gmail token management via Spring Security
- Seamless OAuth2 login flow (`/login/oauth2/code/google`)
- Direct access to Gmail API using `OAuth2AuthorizedClient`
- Auto-creation and activation of users on first OAuth2 login

## Configuration

### 1. Environment Variables

Add these to your `.env` file (project root):

```bash
# Google OAuth2 Application Credentials
# Get these from Google Cloud Console
GOOGLE_CLIENT_ID=your-client-id.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=your-client-secret

# OAuth2 Redirect URI (matches what you set in Google Cloud Console)
GOOGLE_REDIRECT_URI=http://localhost:8080/login/oauth2/code/google

# For production
# GOOGLE_REDIRECT_URI=https://yourdomain.com/login/oauth2/code/google
```

### 2. Google Cloud Console Setup

1. Go to [Google Cloud Console](https://console.cloud.google.com)
2. Create a new project (e.g., "BuildAssist")
3. Enable these APIs:
   - Gmail API
   - Google+ API (for user profile)
   - OAuth 2.0 Consent Screen

4. Create OAuth 2.0 Credentials:
   - Type: OAuth 2.0 Client ID
   - Application type: Web application
   - Authorized redirect URIs:
     - `http://localhost:8080/login/oauth2/code/google` (local dev)
     - `https://yourdomain.com/login/oauth2/code/google` (production)
   - Authorized JavaScript origins:
     - `http://localhost:3000` (local dev)
     - `https://yourdomain.com` (production)

5. Copy Client ID and Client Secret → set in `.env`

### 3. Required Scopes

Scopes are configured in `application.yml` under `spring.security.oauth2.client.registration.google.scope`:

```yaml
scope:
  - openid               # Standard OpenID Connect
  - email                # Access email
  - profile              # Access profile info
  - https://www.googleapis.com/auth/gmail.readonly   # Read emails
  - https://www.googleapis.com/auth/gmail.compose    # Send emails
```

**Note:** Scopes requesting Gmail permissions will require additional OAuth consent screen setup in Google Cloud.

## API Endpoints

### 1. OAuth2 Login (Browser)

Navigate to:
```
http://localhost:8080/login/oauth2/authorization/google
```

This redirects to Google login, then back to `/login/oauth2/code/google` where Spring Security handles the OAuth2 callback.

**Success Response:** JSON with JWT token and user info
```json
{
  "token": "eyJhbGciOiJIUzUxMiJ9...",
  "email": "user@gmail.com",
  "name": "User Name"
}
```

### 2. Get Current User Info

```bash
GET /api/auth/user
Authorization: Bearer <jwt-token>
```

**Response:**
```json
{
  "email": "user@gmail.com",
  "name": "User Name",
  "picture": "https://...",
  "id": "google-user-id"
}
```

### 3. List User Emails (Future Implementation)

Once `EmailService.listEmails()` is implemented, it will use the OAuth2 token from Spring Security:

```bash
GET /api/emails
Authorization: Bearer <jwt-token>
```

## Code Architecture

### Security Configuration

**File:** `SecurityConfig.java`

```java
.oauth2Login(oauth2 -> oauth2
    .successHandler(oauth2SuccessHandler))
```

- Enables OAuth2 login flow
- Delegates success handling to `OAuth2AuthenticationSuccessHandler`
- Maintains stateless JWT-based API authentication

### OAuth2 Success Handler

**File:** `OAuth2AuthenticationSuccessHandler.java`

Handles successful OAuth2 authentication by:

1. Extracting user email and name from OAuth2 principal
2. Finding or creating User in database
3. Auto-activating new OAuth2 users
4. Generating JWT token via `JwtTokenProvider`
5. Returning token in JSON response

### Getting OAuth2 Access Token

In services, retrieve the OAuth2 token for Gmail API calls:

```java
@Service
public class EmailService {
    
    private final OAuth2AuthorizedClientService oauth2ClientService;
    
    public void fetchEmails(String principalName) {
        OAuth2AuthorizedClient client = oauth2ClientService
            .loadAuthorizedClient("google", principalName);
        
        if (client != null) {
            String accessToken = client.getAccessToken().getTokenValue();
            // Use accessToken to call Gmail API
        }
    }
}
```

### GmailService Updates

**File:** `GmailService.java`

Key changes:

- Added `getOAuth2AccessToken(String principalName)` method
- Returns access token from Spring Security context
- Allows safe Gmail API calls without manual token handling

**Usage in services:**
```java
Optional<String> token = gmailService.getOAuth2AccessToken(userEmail);
if (token.isPresent()) {
    // Use token.get() for Gmail API calls
}
```

## Frontend Integration

### 1. Google Login Button

The frontend should navigate to:
```javascript
window.location.href = 'http://localhost:8080/login/oauth2/authorization/google';
```

Or use a custom Google Login button.

### 2. Handle OAuth2 Callback

The backend returns a JSON response after successful login. The frontend should:

1. Extract the JWT token
2. Store in localStorage/sessionStorage
3. Use in subsequent API calls via `Authorization: Bearer <token>`

Example:
```javascript
// After redirect from /login/oauth2/code/google
const response = await fetch('http://localhost:8080/api/auth/user', {
  headers: { 'Authorization': `Bearer ${token}` }
});
```

## Security Considerations

### JWT Token Flow

1. User logs in via Google OAuth2
2. `OAuth2AuthenticationSuccessHandler` generates stateless JWT token
3. Token contains userId and email claims
4. Frontend stores JWT and uses for subsequent API calls
5. Backend validates JWT via `JwtAuthenticationFilter`

### Stateless API

- No server-side sessions (SessionCreationPolicy.STATELESS)
- Each request authenticated via JWT
- OAuth2 context only used during login

### Token Encryption

Gmail tokens in database (if stored) must be encrypted:

```java
conn.setAccessTokenEncrypted(
    tokenEncryptionService.encrypt(accessToken)
);
```

Use `app.encryption.aes-key` from environment.

## Troubleshooting

### Issue: "Client ID is missing"

**Solution:** Ensure `GOOGLE_CLIENT_ID` is set in `.env` and loaded by the backend.

```bash
# Verify env loading
echo $GOOGLE_CLIENT_ID
```

Run backend with:
```powershell
./scripts/run-backend.ps1  # Windows
# or
cd backend && mvn spring-boot:run  # Manual (after setting env vars)
```

### Issue: "Invalid redirect URI"

**Solution:** Ensure the redirect URI matches exactly:

1. In Google Cloud Console
2. In `GOOGLE_REDIRECT_URI` environment variable
3. In `application.yml` `spring.security.oauth2.client.registration.google.redirect-uri`

### Issue: "Scopes not granted"

**Solution:** OAuth2 consent screen may need configuration:

1. Go to Google Cloud Console → OAuth Consent Screen
2. Set User Type: "External" (for testing)
3. Add test users with Gmail access
4. Verify requested scopes are listed

### Issue: Login redirects to error

**Solution:** Check backend logs for:

```
DEBUG com.buildassist.security.OAuth2AuthenticationSuccessHandler
```

Common errors:
- User not found (create via endpoint first)
- Database connection issue
- Invalid JWT secret (min 32 chars)

## Running Locally

### Prerequisites

```bash
# Database & Redis
docker compose up -d postgres redis

# Backend (load env vars)
./scripts/run-backend.ps1  # Windows
# or on macOS/Linux:
cd backend && mvn spring-boot:run

# Frontend
cd frontend && npm run dev
```

### Verify Setup

```bash
# Check backend is running
curl http://localhost:8080/actuator/health

# Check OAuth2 endpoints
curl http://localhost:8080/login/oauth2/authorization/google
# Should redirect to Google login
```

## Next Steps

1. **Implement `EmailService.listEmails()`** - Use OAuth2 token to fetch emails
2. **Implement `EmailService.refreshFromGmail()`** - Persist emails to database
3. **Set up frontend OAuth2 login** - Add Google login button to UI
4. **Token refresh handling** - Handle expired tokens gracefully

## References

- [Spring Security OAuth2 Client Documentation](https://spring.io/projects/spring-security#learn)
- [Google OAuth2 Scopes](https://developers.google.com/identity/protocols/oauth2/scopes)
- [Gmail API Documentation](https://developers.google.com/gmail/api/guides)
- [Spring Security OAuth2 in Spring Boot](https://spring.io/guides/tutorials/spring-boot-oauth2/)

