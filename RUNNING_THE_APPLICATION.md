# Running CampusCode Locally (Windows)

This guide covers the local setup: Spring Boot, PostgreSQL, Cognee, the Cognee UI, and Mailpit.

## 1. Prerequisites

- Docker Desktop running with Docker Compose available in PowerShell.
- Java 21 and Maven 3.9 or later.
- A Gemini API key.
- Postman or another HTTP client for API requests.

Run commands below from the project directory:

```powershell
Set-Location "C:\Users\Asus\Documents\ChatGPT\Cognee Setup"
```

## 2. Configure `.env`

If you do not already have a `.env`, create one from the example:

```powershell
Copy-Item .env.example .env
```

If `.env` already exists, **edit it**; do not copy over it. Set `GEMINI_API_KEY` to your Google AI Studio key. 
For PostgreSQL, if you override the credentials, keep `POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD` in sync 
with `DATABASE_URL`/`DATABASE_USERNAME`/`DATABASE_PASSWORD`. 
The defaults use database `campuscode`, user `campuscode`, password `campuscode_dev_only`, and host port `5433`.

For local mail capture, make sure these settings are present:

```dotenv
MAIL_HOST=localhost
MAIL_PORT=1025
MAIL_USERNAME=
MAIL_PASSWORD=
MAIL_FROM=campuscode@localhost
MAIL_SMTP_AUTH=false
MAIL_SMTP_STARTTLS=false
```

The `.env` file is local configuration and must not be committed.
Keep `COGNEE_USERNAME`/`COGNEE_PASSWORD` identical to `DEFAULT_USER_EMAIL`/`DEFAULT_USER_PASSWORD`; Spring uses this local Cognee account to authenticate its REST calls.

## 3. Start Cognee and PostgreSQL

Start the default Docker services:

```powershell
docker compose up -d
docker compose ps
```

This starts PostgreSQL on `localhost:5433` and Cognee on `localhost:8000`. 
Cognee uses local FastEmbed embeddings, Kuzu graph storage, and LanceDB vector storage. 
Its files persist in the `cognee_storage` Docker volume; PostgreSQL persists in `campuscode_postgres`.
Cognee is configured for per-dataset graph/vector isolation and requires authentication. Spring logs in as the configured default Cognee user before calling its API. Existing shared-store data is not migrated when switching to isolation; the files remain in the volume, but re-ingest repositories before relying on evaluation or graph views. Do not run `docker compose down -v` as a migration step.

Check Cognee health and PostgreSQL readiness:

```powershell
Invoke-RestMethod http://localhost:8000/health
docker compose exec postgres pg_isready -U campuscode -d campuscode
```

The Cognee API docs are at <http://localhost:8000/docs>.

## 4. Start the optional Cognee graph UI

```powershell
docker compose --profile ui up -d cognee-ui
```

Open <http://localhost:3000>, sign in using the configured Cognee default account, then use **Datasets** and **Graph** to inspect data. Per-dataset storage prevents graph traversal from mixing repository datasets.

## 5. Start Mailpit

```powershell
docker compose --profile mail up -d mailpit
```

Open the captured-email inbox at <http://localhost:8025>. CampusCode sends mail to Mailpit on `localhost:1025`;
Mailpit captures it locally and does not deliver it to a real inbox.

## 6. Start CampusCode

From the project root, either run:

```powershell
mvn spring-boot:run
```

or open the project in IntelliJ IDEA and run `CampusCodeApplication`. The project imports `.env` from the working directory, 
so make sure IntelliJ's run configuration uses the project root as its working directory.

Check the API health endpoint:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

The API listens on `http://localhost:8080`.

## 7. Ingest a public GitHub repository

Create an ingestion. Include `notificationEmail` only if you want to test the email notification; 
use a new repository for that test because a duplicate submission reuses the existing job and does not update its notification address.

```powershell
$request = @{
  repositoryUrl = "https://github.com/owner/repository"
  notificationEmail = "you@example.com"
} | ConvertTo-Json

$ingestion = Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8080/api/repositories/ingestions" `
  -ContentType "application/json" `
  -Body $request

$ingestion
```

The API returns `202 Accepted` with an ingestion `id`. Save it and check status until it is `COMPLETED`:

```powershell
$ingestionId = $ingestion.id
Invoke-RestMethod "http://localhost:8080/api/repositories/ingestions/$ingestionId"
```

## 8. Evaluate the repository

For progress followed by the final scorecard in one request, use Postman:

```http
POST http://localhost:8080/api/repositories/ingestions/{ingestionId}/evaluations/stream
Accept: text/event-stream
```

Replace `{ingestionId}` with the ID returned by ingestion. The stream reports progress and ends 
with `completed` and the saved scorecard, or `failed` with an error. Evaluation requires ingestion status `COMPLETED`.

The existing asynchronous JSON workflow remains available:

```http
POST http://localhost:8080/api/repositories/ingestions/{ingestionId}/evaluations
GET  http://localhost:8080/api/repositories/evaluations/{evaluationId}
GET  http://localhost:8080/api/repositories/ingestions/{ingestionId}/report
```

The scorecard rates architecture, maintainability, testing, reliability, security, and performance/scalability. 
Citations include evidence excerpts and repository paths. The report also includes strengths, areas for improvement, and a score explanation for every dimension. The exact context sent to Gemini can be inspected with:

```http
GET http://localhost:8080/api/repositories/ingestions/{ingestionId}/report/evidence
```

Retrieval audit logs report each Cognee result's repository-relative path, line range, content length, and citation discard reason. If you need to inspect the actual bounded excerpts and clipped raw Cognee response, add these to `.env` and restart Spring Boot:

```properties
COGNEE_RETRIEVAL_AUDIT_CONTENT=true
COGNEE_RETRIEVAL_AUDIT_PREVIEW_CHARS=500
CAMPUSCODE_EVALUATION_LOG_LEVEL=DEBUG
```

Turn content auditing back off after debugging because repository code may contain secrets. Markdown graph facts remain supporting context only. An evaluation with no citation-ready source-code passages now fails with a diagnostic instead of being saved as a completed zero-score report.

## 9. Verify the email notification

CampusCode sends a notification **after the evaluation report is saved**. After the stream reports `completed`, 
refresh <http://localhost:8025>. The test recipient can be any address because Mailpit captures the message. 
If the inbox is empty, check that the ingestion request included `notificationEmail`, that CampusCode was restarted 
after mail settings were added, and that evaluation completed successfully.

## Other useful endpoints

Search repository evidence without asking Cognee to write an answer:

```http
POST http://localhost:8080/api/repositories/search
Content-Type: application/json

{
  "repositoryUrl": "https://github.com/owner/repository",
  "query": "How does the application authenticate users?"
}
```

Open a repository's interactive Cognee graph after its ingestion reaches `COMPLETED`:

```http
GET http://localhost:8080/api/repositories/graph/visualization?repositoryUrl=https%3A%2F%2Fgithub.com%2Fowner%2Frepository
Accept: text/html
```

The React graph page uses this Spring endpoint and renders its HTML response in a sandboxed iframe. The browser does not call Cognee directly. You can also open the completed ingestion page in the frontend and choose **Explore knowledge graph**.

## PostgreSQL in IntelliJ IDEA

Create a PostgreSQL data source with:

- Host: `localhost`
- Port: `5433`
- Database: `campuscode`
- User: `campuscode`
- Password: the `POSTGRES_PASSWORD` value in `.env`; if unset, the Compose default is `campuscode_dev_only`.

The IDE data source lets you browse the database; Docker Compose runs PostgreSQL and Spring Boot uses the same connection settings.

## Logs and stopping services

```powershell
docker compose logs -f cognee
docker compose logs -f postgres
docker compose --profile mail logs -f mailpit
docker compose ps
```

Stop the containers while preserving their named volumes and stored data:

```powershell
docker compose --profile ui --profile mail stop
```

Stop CampusCode with `Ctrl+C` in its terminal or the IntelliJ Stop button. 
Start the containers again with the same `docker compose` commands above.
