# AGENTS — Guide for coding agents (concise)

Checklist for an agent starting work
- Inspect `backend/src/main/java/com/buildassist/service` → implement missing service methods (see below)
- Load env and run backend: `cd backend && mvn spring-boot:run` or on Windows `./scripts/run-backend.ps1`
- Start PostgreSQL+Redis for local dev: `docker compose up -d postgres redis`
- Run frontend: `cd frontend && npm install && npm run dev`

Big-picture architecture (what matters quickly)
- Backend: Spring Boot API (package com.buildassist). Controllers are thin and delegate to services in `backend/src/main/java/com/buildassist/service`.
- Frontend: Next.js (App Router). It uses `frontend/lib/api.ts` which calls `${API_BASE_URL}/...` (configured by `NEXT_PUBLIC_API_URL`).
- Proxy/deploy: `deploy/Caddyfile` routes `/api/*` → backend (`app:8080`) and `/` → frontend. `docker-compose.yml` wires services and sets `DATABASE_URL`, `REDIS_HOST` for containers.

Where to implement AI / Gmail work (high-impact files)
- **Gmail OAuth** (IMPLEMENTED): `GmailService` — `getAuthorizationUrl`, `handleOAuthCallback`, and `getConnectionStatus` are complete with AES token encryption via `TokenEncryptionService`.
- **Spring Security OAuth2** (IMPLEMENTED): `SecurityConfig` enables oauth2Login() with Google provider. `OAuth2AuthenticationSuccessHandler` auto-creates users and generates JWT tokens. `AuthController.getCurrentUser()` returns authenticated user info.
 - Claude integration: `backend/src/main/java/com/buildassist/service/ClaudeService.java` — implement `generateReplyDraft(String subject, String fromAddress, String bodyText)`. Use the Anthropic API key configured at `app.anthropic.api-key`.
   Note: the current `AppProperties.Anthropic` only exposes an `apiKey` (no model field). If you need to select a model, add a `model` property to `AppProperties.Anthropic` and `application.yml`, or hardcode a model name when calling the Anthropic API.
- Draft orchestration: `DraftService.generateDraft(Long userId, Long emailId)` → call `ClaudeService`, query email via repositories, create `email_drafts` record, return `GenerateDraftResponse`.
- Email fetch & list: `EmailService` methods (`listEmails`, `getEmail`, `refreshFromGmail`) → use OAuth2 access tokens from `GmailService.getOAuth2AccessToken()`, call Gmail API, query/persist `EmailRepository`.

Note on Gmail integration details:
- `GmailService.getOAuth2AccessToken(String principalName)` uses Spring's `OAuth2AuthorizedClientService.loadAuthorizedClient("google", principalName)` under the hood. The `principalName` must match the OAuth principal used during authorization (in this app that is the user's Gmail address returned by Google). In practice call it with the user's email (e.g. `gmailService.getOAuth2AccessToken(userEmail)`).
- `GmailService` also contains a legacy `handleOAuthCallback` which stores encrypted tokens in the `gmail_connections` table (fields `access_token_encrypted`, `refresh_token_encrypted`). Use `TokenEncryptionService.decrypt(...)` to access these when implementing any manual server-side token flows or fallback refresh logic.
- Prefer the Spring Security OAuth2 authorized-client approach for fetching tokens; use the stored `GmailConnection` only as a fallback for offline/manual flows.
 - Note about scopes: `backend/src/main/resources/application.yml` configures the OAuth2 scopes (includes `https://www.googleapis.com/auth/gmail.compose`) and is what `oauth2Login()` uses in normal operation. The legacy `GmailService.getAuthorizationUrl(...)` constructs a manual authorization URL that uses `https://www.googleapis.com/auth/gmail.send` for the legacy flow. Prefer the scopes configured in `application.yml` / Spring Security; the manual URL is only for legacy/offline flows and may use a slightly different Gmail scope.

Key conventions & patterns to follow
- DTOs are Java records under `backend/src/main/java/com/buildassist/dto` (e.g. `DraftDtos`, `EmailDtos`). Return shapes are strict — change controllers only when DTOs change.
- Controllers throw no logic; business code lives in services. Many services currently throw `UnsupportedOperationException` — implement there.
- Auth: JWT subject is the numeric userId (Long). `JwtAuthenticationFilter` sets Authentication principal to Long; use `SecurityUtils.currentUserId()` in services to get the caller id.
- Token encryption: plaintext Gmail tokens must be encrypted using `TokenEncryptionService` and `app.encryption.aes-key` from `application.yml`.
- Repository access: Use Spring Data JPA repositories (auto-wired in services) — e.g. `UserRepository`, `GmailConnectionRepository`, `EmailRepository`, `EmailDraftRepository`. Methods like `findByUserId()` return `Optional<T>`.
- Error handling: `GlobalExceptionHandler` in `controller/` catches Spring exceptions and returns proper HTTP codes. Custom exceptions (validation, auth) should be thrown from services; the handler maps them to 400/401/404 responses.

Important config & runtime notes
- All runtime config lives in `backend/src/main/resources/application.yml` and is overridable from `.env` (project root). Important keys:
  - `app.anthropic.api-key` (ANTHROPIC_API_KEY)
  - `app.gmail.client-id` / `client-secret` / `redirect-uri` (legacy OAuth)
  - `app.encryption.aes-key` (32-byte key expected)
  - `app.jwt.secret` (must be at least 32 chars for HMAC signing)
  - `spring.security.oauth2.client.registration.google.*` (GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REDIRECT_URI)
- OAuth2 Login Flow: `GET /login/oauth2/authorization/google` initiates login → redirects to Google → callback at `/login/oauth2/code/google` (auto-handled by Spring) → `OAuth2AuthenticationSuccessHandler` creates/updates user, generates JWT → frontend receives token in JSON response
- Swagger UI: `http://localhost:8080/swagger-ui.html` exposes controllers/DTOs for quick inspection.

Data model & persistence pointers
- Flyway migration: `backend/src/main/resources/db/migration/V1__init_schema.sql` — tables: `users`, `gmail_connections`, `emails`, `email_drafts`.
- `GmailConnection` entity maps encrypted tokens to the `gmail_connections` row. `email_drafts` status CHECK allows only `DRAFT` or `SENT`.

Examples (API surfaces agents will call or extend)
- Generate draft: POST /api/emails/{emailId}/drafts/generate → implemented by `DraftController.generate` → implement `DraftService.generateDraft(userId,emailId)` to return `DraftDtos.GenerateDraftResponse`.
- Send draft: POST /api/emails/{id}/drafts/{draftId}/send → call Gmail API with decrypted token and update `email_drafts.status` to `SENT`.

Debugging & dev workflow tips
- Use `./scripts/run-backend.ps1` on Windows to load `.env` into process env before `mvn spring-boot:run`.
- For containerized full-stack: `docker compose up --build` (root). Caddy will route frontend and API as in `deploy/Caddyfile`.
- Use Swagger for request/response examples and to verify security constraints (some routes are public — e.g. `/api/auth/**`, `/api/gmail/callback`).
- Testing: Unit test services in `backend/src/test/java/com/buildassist/service/` using `@DataJpaTest` + repositories or `@SpringBootTest` for integration. Mock external calls (Gmail, Anthropic) with `@MockBean`.
- Logging: Use Spring's `@Slf4j` logger or `LoggerFactory`. No structured logging configured yet; logs output to console in dev.

Goals for the first PR from an agent
1. Implement `EmailService.listEmails()` using OAuth2 access token from `GmailService.getOAuth2AccessToken()` to fetch emails via Gmail API, persist to `EmailRepository`.
2. Implement `EmailService.refreshFromGmail()` to fetch latest 50 emails and update `emails` table.
3. Implement `ClaudeService.generateReplyDraft()` to call Anthropic API (honor `app.anthropic.api-key`) and return plain text reply draft.
4. Implement `DraftService.generateDraft()` to call `ClaudeService`, query email via `EmailRepository`, create `email_drafts` record, and return `GenerateDraftResponse`.
5. Add unit tests for services and verify via Swagger + frontend flow against local backend.

Files to open first (fast scan)
- `backend/src/main/java/com/buildassist/service/ClaudeService.java`
- `backend/src/main/java/com/buildassist/service/DraftService.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V1__init_schema.sql`
- `frontend/lib/api.ts` and `frontend/lib/config.ts`
 - `backend/src/main/java/com/buildassist/service/GmailService.java` (token retrieval, legacy callback)
 - `backend/src/main/java/com/buildassist/service/TokenEncryptionService.java` (AES-GCM encryption/decryption)
 - `backend/src/main/java/com/buildassist/security/OAuth2AuthenticationSuccessHandler.java` (creates users, issues JWT on oauth2 login)

Data access patterns (models & repositories)
- **Models**: `User`, `GmailConnection`, `Email`, `EmailDraft` in `backend/src/main/java/com/buildassist/model/`
- **Repositories** (Spring Data JPA interfaces in `backend/.../repository/`):
  - `UserRepository.findById(Long)` → `Optional<User>`
  - `GmailConnectionRepository.findByUserId(Long)` → `Optional<GmailConnection>` (contains encrypted tokens)
  - `EmailRepository.findByGmailConnectionId(...)` and `findById(Long)`
  - `EmailDraftRepository.findById(...)` and create new `EmailDraft` entities
- Decrypt tokens from `GmailConnection`: `tokenEncryptionService.decrypt(connection.getAccessTokenEncrypted())`

OAuth2 & Gmail Integration
- **Spring Security OAuth2 Login**: Configured in `SecurityConfig` and `application.yml` under `spring.security.oauth2.client.registration.google`. Users login via `GET /login/oauth2/authorization/google` → Spring auto-handles callback → `OAuth2AuthenticationSuccessHandler` creates user and issues JWT.
- **Gmail API Access**: Use `GmailService.getOAuth2AccessToken(String principalName)` to retrieve access token from Spring Security context. Token is automatically managed and refreshed by Spring Security. Example: `Optional<String> token = gmailService.getOAuth2AccessToken(userEmail); if(token.isPresent()) { /* call Gmail API */ }`
- **Required Scopes** (configured in `application.yml`): `openid`, `email`, `profile`, `https://www.googleapis.com/auth/gmail.readonly`, `https://www.googleapis.com/auth/gmail.compose`
- **JWT Token Flow**: After successful OAuth2 login, JWT token is returned to frontend. Frontend includes in `Authorization: Bearer <token>` header for all API calls. Token contains userId and email. Expires after 24 hours (configurable).

If you need more context, inspect `README.md` for environment and quick-start notes.

