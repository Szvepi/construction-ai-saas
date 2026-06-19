# OAuth2 Integration - Quick Reference

## Quick Start Checklist

### Prerequisites
- [ ] Docker with Docker Compose
- [ ] Java 21
- [ ] Maven 3.6+
- [ ] Node.js 20+ (frontend)
- [ ] Google Cloud Project with OAuth2 credentials

### Setup (5 minutes)

```bash
# 1. Copy env template
cp .env.example .env

# 2. Add Google OAuth2 credentials to .env
# GOOGLE_CLIENT_ID=xxx.apps.googleusercontent.com
# GOOGLE_CLIENT_SECRET=yyy
# GOOGLE_REDIRECT_URI=http://localhost:8080/login/oauth2/code/google

# 3. Start database & cache
docker compose up -d postgres redis

# 4. Run backend
./scripts/run-backend.ps1  # Windows
# or: cd backend && mvn spring-boot:run

# 5. Run frontend (optional)
cd frontend && npm install && npm run dev
```

## API Reference

### OAuth2 Login
```
GET http://localhost:8080/login/oauth2/authorization/google
```
Redirects to Google login → back to `/login/oauth2/code/google` → returns JWT

**Response (JSON):**
```json
{
  "token": "eyJhbGciOiJIUzUxMiJ9...",
  "email": "user@gmail.com",
  "name": "User Name"
}
```

### Get Authenticated User
```
GET /api/auth/user
Authorization: Bearer <token>
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

## Code Examples

### Retrieving OAuth2 Access Token (for Gmail API)

**In a Service:**
```java
@Service
public class EmailService {
    
    private final GmailService gmailService;
    
    public void fetchEmails(String userEmail) {
        // Get access token from Spring Security context
        Optional<String> token = gmailService.getOAuth2AccessToken(userEmail);
        
        if (token.isPresent()) {
            String accessToken = token.get();
            // Use to call Gmail API
            // Example:
            // GET https://www.googleapis.com/gmail/v1/users/me/messages
            // Header: Authorization: Bearer {accessToken}
        }
    }
}
```

### Frontend OAuth2 Login

```javascript
// Simple approach: redirect to backend
function loginWithGoogle() {
    window.location.href = 'http://localhost:8080/login/oauth2/authorization/google';
}

// After redirect, JWT is in response
// Store and use in future API calls
const token = localStorage.getItem('jwt_token');
fetch('http://localhost:8080/api/auth/user', {
    headers: { 'Authorization': `Bearer ${token}` }
});
```

## File Reference

### Core OAuth2 Files
| File | Purpose |
|------|---------|
| `SecurityConfig.java` | Spring Security + OAuth2 configuration |
| `OAuth2AuthenticationSuccessHandler.java` | Handles OAuth2 callback, generates JWT |
| `AuthController.java` | Exposes `/api/auth/user` endpoint |
| `GmailService.java` | Retrieves OAuth2 access tokens |
| `application.yml` | OAuth2 client registration |

### Models & Data Access
| File | Purpose |
|------|---------|
| `User.java` | User entity |
| `GmailConnection.java` | Stores encrypted Gmail tokens (legacy) |
| `UserRepository.java` | User data access |
| `GmailConnectionRepository.java` | Gmail connection data access |

## Environment Variables

| Variable | Purpose |
|----------|---------|
| `GOOGLE_CLIENT_ID` | OAuth app ID from Google Cloud |
| `GOOGLE_CLIENT_SECRET` | OAuth app secret |
| `GOOGLE_REDIRECT_URI` | OAuth2 redirect (must match Google console) |
| `JWT_SECRET` | JWT signing key (min 32 chars) |

## Troubleshooting

### Port already in use
```bash
# Kill process on port 8080
lsof -ti:8080 | xargs kill -9  # macOS/Linux
netstat -ano | findstr :8080   # Windows
```

### Build fails
```bash
cd backend
mvn clean compile
# Check error messages, ensure Java 21 installed
```

### OAuth2 login fails
1. Check `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` in `.env`
2. Verify redirect URI matches Google Cloud Console exactly
3. Check backend logs for error details
4. Ensure database is running: `docker compose ps`

### JWT token rejected
1. Ensure `JWT_SECRET` is set and at least 32 characters
2. Check token hasn't expired (24 hours default)
3. Verify `Authorization` header format: `Bearer <token>`

## Common Tasks

### Test OAuth2 Flow
```bash
# 1. Navigate to
http://localhost:8080/login/oauth2/authorization/google

# 2. After Google login, you'll be redirected
# Response contains JWT token

# 3. Test authenticated endpoint
curl -H "Authorization: Bearer <token>" \
  http://localhost:8080/api/auth/user
```

### Fetch Emails Using OAuth2 Token
```java
public class EmailService {
    public List<Email> listEmails(String userEmail) {
        Optional<String> accessToken = gmailService.getOAuth2AccessToken(userEmail);
        
        if (accessToken.isEmpty()) {
            throw new RuntimeException("No OAuth2 token available");
        }
        
        // Use accessToken to call Gmail API
        String token = accessToken.get();
        RestTemplate restTemplate = new RestTemplate();
        
        String url = "https://www.googleapis.com/gmail/v1/users/me/messages";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        
        ResponseEntity<String> response = restTemplate.exchange(
            url, HttpMethod.GET, new HttpEntity<>(headers), String.class
        );
        
        // Parse response and return emails
        return parseGmailResponse(response.getBody());
    }
}
```

### Implement Email Refresh
```java
@Service
public class EmailService {
    
    private final EmailRepository emailRepository;
    private final GmailService gmailService;
    
    public RefreshEmailsResponse refreshFromGmail(Long userId) {
        // Get user's Gmail email
        User user = userRepository.findById(userId).orElseThrow();
        
        // Get OAuth2 access token
        Optional<String> token = gmailService.getOAuth2AccessToken(user.getEmail());
        if (token.isEmpty()) {
            throw new RuntimeException("No Gmail connection");
        }
        
        // Fetch from Gmail API
        List<Email> emails = fetchLatestEmails(token.get(), 50);
        
        // Save to database
        emailRepository.saveAll(emails);
        
        return new RefreshEmailsResponse(emails.size());
    }
    
    private List<Email> fetchLatestEmails(String accessToken, int maxResults) {
        // Implementation using Gmail API
        // ...
    }
}
```

## Important Notes

### Stateless Authentication
- All API requests use JWT tokens (no sessions)
- OAuth2 context only active during login
- Tokens expire after 24 hours (configurable)

### Token Security
- JWT tokens are signed, not encrypted
- Never send in URLs, always use Authorization header
- Store securely in frontend (httpOnly cookies preferred)

### Production Deployment
- Change `GOOGLE_REDIRECT_URI` to production domain
- Use HTTPS (required by Google)
- Set strong `JWT_SECRET` (generate with: `openssl rand -base64 32`)
- Rotate `GOOGLE_CLIENT_SECRET` periodically
- Monitor token usage

## Next Implementation Steps

1. **Implement EmailService.listEmails()**
   - Use OAuth2 token from GmailService
   - Call Gmail API to list messages
   - Persist to emails table

2. **Implement EmailService.refreshFromGmail()**
   - Fetch latest N emails from Gmail
   - Update emails table
   - Return refresh statistics

3. **Add Gmail Draft Sending**
   - Use OAuth2 token to send via Gmail API
   - Update email_drafts status to SENT
   - Handle errors gracefully

4. **Frontend OAuth2 Button**
   - Add "Login with Google" UI button
   - Handle JWT token response
   - Redirect to dashboard on success

## Documentation

- **Full Setup Guide:** `OAUTH2_SETUP.md`
- **Implementation Details:** `OAUTH2_IMPLEMENTATION.md`
- **Agent Guide:** `AGENTS.md`

