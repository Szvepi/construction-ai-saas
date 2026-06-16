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

Backend and frontend run on your machine; only PostgreSQL and Redis run in Docker.

### 1. Environment

```bash
cp .env.example .env
# Edit .env with your secrets
```

Use **localhost** for DB/Redis in `.env` (defaults in `application.yml` match):

| Variable | Local dev value |
|----------|-----------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/buildassist` |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |

The Docker `app` service overrides these to `postgres` / `redis` hostnames internally.

### 2. Database & Redis (Docker)

```bash
docker compose up -d postgres redis
```

Exposed on the host (bind `127.0.0.1` only):

- PostgreSQL → `localhost:5432` (override with `POSTGRES_PORT`)
- Redis → `localhost:6379` (override with `REDIS_PORT`)

Verify:

```bash
docker compose ps
# psql: docker compose exec postgres psql -U buildassist -d buildassist
# redis: docker compose exec redis redis-cli ping
```

### 3. Backend

```bash
cd backend
mvn spring-boot:run
```

Windows (loads `.env` automatically):

```powershell
.\scripts\run-backend.ps1
```

Or set env vars in your IDE run configuration from the root `.env` file.

API: `http://localhost:8080` — Swagger: `http://localhost:8080/swagger-ui.html`

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
