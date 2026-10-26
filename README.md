# MIRA – Az okos asszisztens az építkezésen

Ultra-minimal AI email assistant for small construction companies (Hungary). MVP scaffold — business logic is
placeholder only.

## Stack

| Layer    | Tech                                           |
|----------|------------------------------------------------|
| Backend  | Spring Boot 3.3, Java 21, PostgreSQL 16, Redis |
| Frontend | Next.js 14 (App Router), Tailwind CSS          |
| AI       | Claude `claude-haiku-4-5` (not wired yet)      |
| Auth     | Stateless JWT (24h expiry)                     |
| Deploy   | Docker Compose + Caddy                         |

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

| Variable       | Local dev value                                |
|----------------|------------------------------------------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/buildassist` |
| `GMAIL_SYNC_WINDOW_MONTHS` | `6` (limits new Gmail imports; existing stored emails remain searchable) |
| `REDIS_HOST`   | `localhost`                                    |
| `REDIS_PORT`   | `6379`                                         |

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
# Set DOMAIN, secrets, Gmail, OpenAi keys

docker compose up --build
```

Caddy serves the frontend on `/` and proxies `/api/*` to the backend.

## MVP API routes (scaffold)

| Method | Path                                     | Purpose                  |
|--------|------------------------------------------|--------------------------|
| POST   | `/api/auth/register`                     | Register                 |
| POST   | `/api/auth/login`                        | Login → JWT              |
| GET    | `/api/gmail/connect`                     | Gmail OAuth URL          |
| GET    | `/api/gmail/callback`                    | OAuth callback           |
| GET    | `/api/gmail/status`                      | Connection status        |
| GET    | `/api/emails`                            | List emails              |
| GET    | `/api/emails/{id}`                       | Email detail             |
| POST   | `/api/emails/refresh`                    | Fetch last 50 from Gmail |
| POST   | `/api/emails/{id}/drafts/generate`       | Claude draft             |
| PUT    | `/api/emails/{id}/drafts/{draftId}`      | Save draft               |
| POST   | `/api/emails/{id}/drafts/{draftId}/send` | Send via Gmail           |
| POST   | `/api/drafts/analyze/{emailId}`          | AI quote analysis and requested-work coverage |
| POST   | `/api/drafts/{draftId}/save`             | Save quote lines and notes |
| GET    | `/api/drafts/{draftId}/coverage`         | Read current quote coverage and unresolved checks |
| PUT    | `/api/drafts/{draftId}/coverage/{issueId}` | Persist a coverage decision and current quote |
| POST   | `/api/drafts/{draftId}/finalize`         | Create Gmail draft; unresolved checks require `confirmIncomplete: true` |
| POST   | `/api/drafts/{draftId}/preview`          | Save quote and render its PDF preview |

The quote analysis response includes `coverageItems`, `coverageWarnings`, and `analysisFresh`. Coverage decisions are stored per draft and recognized work. A `coverageItems` entry can be marked `INCLUDED`, `NEEDS_REVIEW`, `MISSING`, or `EXCLUDED`; an included user decision is linked to a line item ID and its scope fingerprint. The finalize endpoint recalculates unresolved checks from the saved draft before contacting Gmail and does not create a draft unless the request explicitly confirms unresolved checks.

## Database

Flyway migrations are in `backend/src/main/resources/db/migration/`. `V15__draft_quote_coverage.sql` adds nullable JSON/text columns for requested work, coverage decisions, warning acknowledgements, the analysis fingerprint, and an optimistic-lock version; old drafts remain readable.

`V4__update_email_drafts_status_check.sql` uses `ADD COLUMN IF NOT EXISTS` because `unmapped_requests` was already added by V3. Databases that already applied the previous V4 contents need a one-time, operator-approved Flyway `repair` to update the V4 checksum before starting the updated application. Take a backup and verify the migration history first; do not run repair automatically during normal startup. The repair updates Flyway history metadata only and does not rerun the migration or change application data.

Tables: `users`, `gmail_connections`, `emails`, `email_drafts`

### Manual verification: quote coverage and Gmail confirmation

1. Analyze a quote-request email for 45 m² of paving and add a separate 15 m² sidewalk in the supplementary notes. With the `WASTE_PERCENTAGE_10` strategy, a 49.5 m² paving line should still cover the 45 m² request, while the separate 15 m² sidewalk remains under review; confirm that each work shows its source.
2. Add a missing work from the coverage panel. Confirm its name is prefilled, any quantity is shown only when the analysis returned one, and its unit price starts at zero for manual entry.
3. Select an existing quote line for “Másik tétel tartalmazza,” exclude a work, and leave another for review. Save, reload the email, and confirm that all three decisions persist.
4. Change or delete a linked line and confirm that its prior coverage decision becomes review-required. Change supplementary notes without reanalysis and confirm that the analysis is marked stale; reanalysis should replace the work list with fresh IDs and clear old decisions for that new analysis.
5. Save and preview while checks remain unresolved. Start Gmail draft creation and verify the accessible confirmation dialog lists the current unresolved checks. Return to the quote and confirm edits are preserved; confirm creation and verify only a Gmail draft (not a sent email) is created.
6. Repeat the create action after success and verify the UI prevents another request. Verify an API finalize call without `confirmIncomplete: true` is rejected when any check remains unresolved.

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
