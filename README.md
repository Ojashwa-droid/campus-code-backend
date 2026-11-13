# CampusCode Local Cognee MVP

CampusCode accepts a public GitHub repository, uses local Cognee to build its code knowledge graph, retrieves repository passages, and evaluates the repository through Spring AI with Gemini. Job history and reports are stored in local PostgreSQL. Cognee does retrieval only during evaluation; Gemini generates the scorecard.

## Requirements

- Java 21 or later
- Maven 3.9+
- Docker Desktop with Compose
- A Gemini API key

## Configure local services

Copy `.env.example` to `.env`, then set `GEMINI_API_KEY` and a local `POSTGRES_PASSWORD`/`DATABASE_PASSWORD`. PostgreSQL is published on localhost port 5433 because port 5432 is commonly occupied by an existing local PostgreSQL installation. Compose passes the Gemini key to Cognee for graph extraction, and Spring Boot reads the same `.env` when started from the project directory. Do not commit `.env`.

Start Cognee and PostgreSQL:

```powershell
docker compose up -d
Invoke-RestMethod http://localhost:8000/health
docker compose ps
```

Cognee uses local FastEmbed embeddings, Kuzu graph storage, and LanceDB vector storage. Its data is retained in the `cognee_storage` volume. PostgreSQL job and report history is retained in `campuscode_postgres`.

Start the API from the project root so it can load `.env`:

```powershell
mvn spring-boot:run
```

The API is at `http://localhost:8080`. The optional Cognee graph UI can be started with `docker compose --profile ui up -d` and opened at `http://localhost:3000`.

## Repository workflow

### 1. Start ingestion

```http
POST /api/repositories/ingestions
Content-Type: application/json

{
  "repositoryUrl": "https://github.com/owner/repository",
  "notificationEmail": "optional@example.com"
}
```

The API returns `202 Accepted` and an ingestion ID. `notificationEmail` is optional. Repeated submissions for a repository reuse its latest non-failed ingestion; failed ingestions can be retried by submitting again.

### 2. Check ingestion status

```http
GET /api/repositories/ingestions/{ingestionId}
```

Wait for `COMPLETED` before evaluation. On service restart, any interrupted job is persisted as `FAILED` with a retry explanation.

### 3. Request an engineering evaluation

```http
POST /api/repositories/ingestions/{ingestionId}/evaluations
```

Evaluation is asynchronous. The response contains an evaluation ID and `QUEUED` status. The scorecard rates architecture, maintainability, testing, reliability, security, and performance/scalability from 0–5 with equal weight. The overall score is the mean normalized to 0–100.

For a single streaming request that reports progress and then returns the scorecard, use the additional event-stream endpoint:

```http
POST /api/repositories/ingestions/{ingestionId}/evaluations/stream
Accept: text/event-stream
```

It emits `progress` events during retrieval, generation, validation, and report saving, followed by `completed` with the saved report or `failed` with the evaluation error. A frontend can call this with `fetch` and read the response stream. This route reuses an active evaluation; the original JSON endpoint above is unchanged.

### 4. Read status and report

```http
GET /api/repositories/evaluations/{evaluationId}
GET /api/repositories/ingestions/{ingestionId}/report
```

Each dimension includes a rationale and citations with a file path, line range, and excerpt. The API checks that the cited evidence ID exists, the excerpt is present in the retrieved passage, and the path matches Cognee source metadata. A dimension without verifiable evidence is returned as score 0 with an insufficient-evidence rationale and no citations. Line ranges are syntax-checked but are supplied by the model because Cognee's retrieved chunks do not consistently expose source line offsets. If an email and SMTP configuration were supplied, CampusCode sends a notification after report persistence; email delivery failure does not invalidate the report.

### Evidence search

The existing retrieval endpoint returns Cognee `CHUNKS` evidence without asking Cognee to generate an answer:

```http
POST /api/repositories/search
Content-Type: application/json

{
  "repositoryUrl": "https://github.com/owner/repository",
  "query": "How does the application authenticate users?"
}
```

Only canonical public GitHub HTTPS repository URLs are supported. CampusCode does not scrape GitHub pages; Cognee receives the repository URL and handles ingestion. The dataset name is deterministic from the canonical repository URL.

### Repository knowledge graph

After a repository ingestion completes, request its Cognee visualization through Spring Boot:

```http
GET /api/repositories/graph/visualization?repositoryUrl=https%3A%2F%2Fgithub.com%2Fowner%2Frepository
Accept: text/html
```

Spring resolves the repository's latest completed ingestion to its Cognee dataset UUID, then fetches `/api/v1/visualize` with that UUID and full-graph mode. The Cognee URL and dataset UUID stay on the backend. The React client fetches this HTML from Spring and displays it in a sandboxed iframe. A repository without a completed ingestion returns an error instead of selecting another dataset.

## Local email testing with Mailpit

Mailpit captures email locally for development, so no Gmail account, SMTP credentials, or external delivery is needed. Add these values to your existing `.env` (copying `.env.example` again would overwrite your current settings):

```dotenv
MAIL_HOST=localhost
MAIL_PORT=1025
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=campuscode@localhost
MAIL_SMTP_AUTH=false
MAIL_SMTP_STARTTLS=false
```

Start the optional Mailpit container and open its inbox:

```powershell
docker compose --profile mail up -d mailpit
docker compose ps mailpit
```

Open <http://localhost:8025>. Spring Boot connects to Mailpit at `localhost:1025`. Restart CampusCode after editing `.env` so it loads the new settings.

To exercise the notification, create a **new ingestion** with `notificationEmail` set, wait for it to complete, then request its evaluation. CampusCode sends the notification after the evaluation report is saved. The recipient can be any address; Mailpit captures it instead of delivering it. Existing ingestions without `notificationEmail` will not send mail, and submitting a repository that already has an active or completed ingestion reuses that job, so use a different public repository for this test.

If SMTP is unset, evaluations still complete and reports remain available through the API; the log explains that no email was sent. For real external delivery later, replace the Mailpit settings with credentials and host details from an SMTP provider.

## Troubleshooting and health

- Cognee: `GET http://localhost:8000/health`
- CampusCode: `GET http://localhost:8080/actuator/health`
- Cognee logs: `docker compose logs -f cognee`
- Database/Cognee startup: `docker compose ps`

Cognee or Gemini errors are recorded in the associated job’s `FAILED` status and error field. Search/evaluation logs include job IDs and stage durations.

## Run the React client

Start CampusCode with `mvn spring-boot:run`, then start the client in another PowerShell window:

```powershell
Set-Location "C:\Users\Asus\Documents\ChatGPT\Cognee Setup\frontend\client"
npm install
npm run dev
```

Open the Vite URL printed in the terminal. The development server proxies `/api` requests to `http://localhost:8080`. The first iteration supports repository submission, ingestion and evaluation progress, and saved scorecards; history and semantic search remain later work. The [frontend README](frontend/client/README.md) includes the client commands, and the [desktop submission target](frontend/design/submission-target.png) records the visual reference used for the first screen.

## Future work

- Add an explicit repository refresh/re-ingestion operation so newer GitHub commits can be indexed and evaluated. Preserve prior ingestion versions and their evaluation reports rather than overwriting historical results.
- Fix Cognee graph mixing across repository datasets. The current Compose setup explicitly sets `ENABLE_BACKEND_ACCESS_CONTROL=false`, which uses shared graph/vector stores for local single-user mode. Enable per-dataset isolation, add the authentication CampusCode needs for Cognee calls, and verify graph/search isolation. Back up the Cognee volume and re-ingest each repository after switching modes; Cognee does not automatically migrate the existing shared graph into per-dataset stores.

See [RUNNING_THE_APPLICATION.md](RUNNING_THE_APPLICATION.md) for the complete Windows local setup and run instructions.

See [FRONTEND_IMPLEMENTATION_PLAN.md](FRONTEND_IMPLEMENTATION_PLAN.md) for the Fern-inspired React client plan, current API mapping, required backend work, and UI acceptance criteria.
