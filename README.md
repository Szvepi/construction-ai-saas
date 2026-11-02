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
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/mira_dev` |
| `GMAIL_SYNC_WINDOW_MONTHS` | `6` (limits new Gmail imports; existing stored emails remain searchable) |
| `GMAIL_PROJECT_QUOTA_UNITS_PER_MINUTE` | `1200000` (must match this Google Cloud project's configured Gmail quota) |
| `GMAIL_USER_QUOTA_UNITS_PER_MINUTE` | `6000` (override for projects with a different per-user quota) |
| `GMAIL_QUOTA_SAFETY_MARGIN` | `0.2` (quota units held in reserve) |
| `GMAIL_GET_METADATA_QUOTA_UNITS` | `20` (Gmail `messages.get?format=metadata`) |
| `GMAIL_GET_FULL_QUOTA_UNITS` | `60` (Gmail `messages.get?format=full`) |
| `REDIS_HOST`   | `localhost`                                    |
| `REDIS_PORT`   | `6379`                                         |

The Docker `app` service overrides these to `postgres` / `redis` hostnames internally.

Gmail API calls go through a Redis-coordinated quota limiter. It accounts for per-method
quota-unit costs, the configured project and per-user limits, a safety margin, and one
in-flight request per Gmail account. Configure the quota values from the actual Google
Cloud project; older projects may have different limits. The used methods cost 5 units
for `messages.list`, 20 for `messages.get?format=metadata`, 60 for
`messages.get?format=full`, 2 for `history.list`, 1 for `getProfile`, and 10 for
`drafts.create`; no `threads.get` call is used. Initial import is newest-first
within the six-month window and continues in the backend after the browser is closed.
Each background task advances one Gmail page, and the scheduler submits at most four
accounts at once so a backlog of older pages cannot fill an unbounded worker queue.
Rate-limit responses are checkpointed and resumed with `Retry-After`-aware exponential
backoff and jitter. After eight consecutive transient failures, a retry cycle is logged
as an operational error and automatically resumes after the capped 15-minute delay
(or a longer Gmail `Retry-After`, if supplied);
successful progress resets the retry counter. Authentication failures wait for Gmail
reconnection instead. Gmail `messages.get` requests use one `format=full` fetch per new
message, which includes the metadata needed for filtering and categorization.

The six-month initial query is restricted to `label:INBOX category:primary`, excluding
Promotions, Social, Updates, spam, and trash; archived and Sent messages are not part of
this inbox import. The Gmail `nextPageToken` is the authoritative continuation marker:
short pages, already-imported IDs, and `resultSizeEstimate` do not end a scan. A page
checkpoint advances only after every returned ID was imported or explicitly skipped and
the database writes succeeded. Invalid or interrupted pages leave the current token in
place so a retry can safely resume without duplicating stored messages.

Sync events (`SYNC_STARTED`, page request/receipt/save, message skip/failure, retry,
resume, completion, and failure) are written to the Spring Boot console at INFO/WARN/
ERROR. The logs include the Gmail connection ID and cursor-presence/offset state, but
never full page tokens, message content, addresses, or OAuth tokens. A completed
six-month initial import is reported separately from a later History refresh that found
no new messages.

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
# psql: docker compose exec postgres psql -U buildassist -d mira_dev
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

The current schema is created by the single `backend/src/main/resources/db/migration/V1__init_schema.sql` Flyway migration. Hibernate uses `ddl-auto: validate`; schema creation and updates are owned by Flyway, not by Hibernate.

The consolidated V1 creates `users`, `gmail_connections`, `emails`, `email_drafts`, `company_profiles`, and `catalog_items`, including Gmail backfill/history state, company tax/VAT settings, catalog pricing and calculation configuration, and the snapshot-backed draft, coverage, fingerprint, and optimistic-version fields. `email_drafts.quote_snapshot` is the sole quote-line/work representation. The old `line_items`, `unmapped_requests`, `requested_work_items`, and `catalog_items.calculation_strategy` columns are not part of this schema.

### Create a clean local database

The commands below create the `mira_dev` database and do not drop or reset an existing database. If the name already exists, stop and inspect it rather than deleting it.

```powershell
docker compose up -d postgres redis
docker compose exec -T postgres psql -U buildassist -d postgres -v ON_ERROR_STOP=1 `
  -c "CREATE DATABASE mira_dev OWNER buildassist;"
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/mira_dev"
mvn -f backend\pom.xml spring-boot:run
```

Flyway applies V1 at startup and Hibernate validates the resulting schema. To apply the optional development catalog fixture, first sign in through Google OAuth so the application creates a local user, then run the fixture against this database with that user's email:

```powershell
$seedEmail = Read-Host "Google account email used for local sign-in"
$seed = Get-Content backend\src\test\resources\db\seed\phase4_catalog_seed.sql -Raw
$seed | docker compose exec -T postgres psql -U buildassist -d mira_dev `
  -v ON_ERROR_STOP=1 -v "seed_email=$seedEmail"
```

The fixture explicitly adds 14 net-priced paving, painting, and electrical catalog items only to the selected existing user's catalog. These prices and defaults (including 15 cm crushed-stone thickness and paint coverage/package-size settings) are development examples, not real contractor prices. First sign in through Google OAuth at `http://localhost:3000`; the OAuth success handler creates the local user. Then run the fixture against `mira_dev`, passing the email returned by Google as `seed_email`. It is not run at application startup or during OAuth login; repeated runs do not add another copy of a fixture item with the same name.

### Backend tests

The `test` Spring profile defaults its URL to `jdbc:postgresql://localhost:5432/mira_test`, requires explicit `TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD`, and runs Flyway with Hibernate schema validation. Point the test variables at a separate, empty test database; do not point them at the normal development database. The profile never falls back to `mira_dev`.

```powershell
$env:TEST_DATABASE_URL = "jdbc:postgresql://localhost:5432/mira_test"
$env:TEST_DATABASE_USERNAME = "<test-only-role>"
$env:TEST_DATABASE_PASSWORD = "<test-only-password>"
mvn -f backend\pom.xml '-Dspring.profiles.active=test' test
```

The historical V1–V19 migration chain has been replaced by the clean V1. A local database that already applied any historical migration is not compatible with this new chain; Flyway `repair` must not be used to mask that schema mismatch. Rebuilding the normal local database is a separate, deliberate cutover: back it up if useful, stop the application, verify the target database name, and recreate it only with explicit operator approval. This change does not reset or modify that database automatically.

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
