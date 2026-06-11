# BuildAssist Email

Ultra-minimal AI email assistant for small construction companies (Hungary). MVP scaffold — business logic is placeholder only.

## Stack

| Layer | Tech |
|-------|------|
| Backend | Spring Boot 3.3, Java 21, PostgreSQL 16, Redis |
| Frontend | Next.js 14 (App Router), Tailwind CSS |
| AI | Claude `claude-haiku-4-5` (not wired yet) |
| Auth | Stateless JWT (24h expiry) |
| Deploy | Docker Compose + Caddy |

## Project layout

```
backend/          Spring Boot API (com.buildassist)
frontend/         Next.js app
deploy/           Caddy reverse proxy config
docker-compose.yml
.env.example
```

## Prerequisites

- Java 21 + Maven (local backend dev)
- Node.js 20+ (local frontend dev)
- Docker & Docker Compose (full stack)
- Google Cloud project with Gmail API + OAuth credentials
- Anthropic API key

## Quick start (local dev)

### 1. Environment

```bash
cp .env.example .env
# Edit .env with your secrets
```

### 2. Database & Redis

```bash
docker compose up -d postgres redis
```

### 3. Backend

```bash
cd backend
mvn spring-boot:run
```

API: `http://localhost:8080` — health: `GET /actuator/health`

### 4. Frontend

```bash
cd frontend
npm install
npm run dev
```

App: `http://localhost:3000`

## Docker Compose (full stack)

```bash
cp .env.example .env
# Set DOMAIN, secrets, Gmail, Anthropic keys

docker compose up --build
```

Caddy serves the frontend on `/` and proxies `/api/*` to the backend.

## MVP API routes (scaffold)

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/auth/register` | Register |
| POST | `/api/auth/login` | Login → JWT |
| GET | `/api/gmail/connect` | Gmail OAuth URL |
| GET | `/api/gmail/callback` | OAuth callback |
| GET | `/api/gmail/status` | Connection status |
| GET | `/api/emails` | List emails |
| GET | `/api/emails/{id}` | Email detail |
| POST | `/api/emails/refresh` | Fetch last 50 from Gmail |
| POST | `/api/emails/{id}/drafts/generate` | Claude draft |
| PUT | `/api/emails/{id}/drafts/{draftId}` | Save draft |
| POST | `/api/emails/{id}/drafts/{draftId}/send` | Send via Gmail |

All protected routes except auth and Gmail callback return `501`/placeholder until implemented.

## Database

Flyway migration: `backend/src/main/resources/db/migration/V1__init_schema.sql`

Tables: `users`, `gmail_connections`, `emails`, `email_drafts`

Activate paid users manually: `UPDATE users SET is_active = true WHERE email = '...';`

## Hardcoded MVP constants

- Claude model: `claude-haiku-4-5`
- Max emails per refresh: `50`
- JWT expiry: `24` hours
- Email sync: manual refresh only

## Security checklist (implement next)

- [ ] BCrypt password hashing in `AuthService`
- [ ] AES-256-GCM for Gmail tokens in `TokenEncryptionService`
- [ ] `is_active` gate on login
- [ ] CORS via `CORS_ALLOWED_ORIGINS`
- [ ] JWT secret from env (min 32 chars)

## License

Private — solo MVP project.
