# CampusCode React Client — Frontend Implementation Plan

**Status:** First frontend iteration implemented in `frontend/client`; the repository work queue and evidence search remain deferred.  
**Product:** CampusCode repository evaluation workbench.  
**Reference reviewed:** Fern homepage, Docs, SDKs, and CLI pages on 2026-11-10.

## 1. Goal and product stance

Build a React client that lets a recruiter submit a public GitHub repository, see ingestion and evaluation progress, and review a scorecard with repository evidence. The primary experience is a focused workbench for evaluating one repository at a time.

Use Fern as a design reference for visual craft and interaction quality. Borrow its strong type hierarchy, restrained dark surfaces, high-contrast CTAs, carefully framed product previews, segmented controls, and crisp code presentation. Do not copy Fern’s logo, illustrations, page copy, exact layout, or branded colors. CampusCode should feel like its own evidence-first engineering assessment product.

Scores should be presented as an AI-assisted, repository-based assessment for human review. Do not present the score as a definitive measure of a person, a hiring recommendation, or an automatic accept/reject decision. The repository is incomplete evidence: a low or zero dimension score may mean the indexed repository did not provide evidence, not that the student lacks the skill.

## 2. Findings from the Fern reference

Pages reviewed:

- [Fern homepage](https://buildwithfern.com/)
- [Fern Docs product page](https://buildwithfern.com/docs)
- [Fern SDKs product page](https://buildwithfern.com/sdks)
- [Fern CLI product page](https://buildwithfern.com/cli)

Patterns worth adapting:

- **A concise, confident hierarchy:** a clear page title, short explanatory text, and one obvious primary action.
- **Product shown in context:** Fern frames its documentation and code in an interface preview, helping visitors understand the actual product instead of relying on decorative hero art.
- **Controls that change meaningful content:** the home page switches between agent-friendly documentation and generated SDK examples; product pages use language and feature selectors to reveal different examples.
- **Strong reading and code surfaces:** generous heading scale, muted supporting text, compact labels, legible line-numbered code, and clear separation between navigation and the active content.
- **Consistent global navigation:** the product area, resources, and account actions stay predictable across pages.
- **Proof is tied to product use:** sections connect a claim to a preview or workflow. CampusCode should use real evaluation evidence and actual job states for proof, never invented customer metrics.
- **Motion has a job:** interactive previews and subtle transitions clarify state or demonstrate a workflow; motion should not delay a recruiter trying to finish a task.

These are observations from the public Fern site, not a request to reproduce it pixel-for-pixel. The supplied MP4 could not be inspected in the browser environment; this plan is based on the live pages above and the CampusCode API/codebase.

## 3. Design contract

- **Thesis:** a repository-to-evidence workbench. The interface should make the evaluation pipeline and its evidence understandable at a glance.
- **Audience:** recruiters and reviewers inspecting public student repositories.
- **Primary action:** submit a repository and follow its evaluation through to the cited report.
- **Visual world:** near-black/charcoal surfaces, near-white primary text, quiet grey secondary text, and a restrained blue/green accent system. Use accents for actual states and focus, not decoration. Use a clean sans-serif UI face and a monospace face only for paths, IDs, and code excerpts. Values are proposed starting roles, not measured copies of Fern’s stylesheet.
- **Layout:** compact app header and workspace navigation; a centered, readable content column for forms and scorecards; denser tables only for repository history and evidence. Use borders sparingly and group with spacing first.
- **Signature interaction:** a live evaluation timeline transitions into a switchable scorecard/evidence view. The control changes real content and remains accessible by keyboard.
- **Motion:** brief state transitions, subtle progress feedback, and reduced motion support. No long entrance sequence or artificial waiting.
- **Authority:** new CampusCode interface, visually informed by Fern’s product pages but with original branding and task-specific structure.
- **Responsive target:** desktop-first reviewer workbench that remains fully usable on tablet and mobile; navigation collapses, forms stack, and score/evidence content becomes a single-column reading flow.

## 4. Proposed application structure

### Navigation

Use a compact top bar with the CampusCode wordmark, **Repositories**, and a prominent **Evaluate repository** action. Put secondary help/about content behind a small menu only if implemented. Avoid adding unsupported account, recruiter-team, candidate-management, or settings areas to the first release.

### Routes

| Route | Purpose |
| --- | --- |
| `/` | Repository work queue and **Evaluate repository** entry point. Needs a backend list endpoint (see API gaps). |
| `/ingestions/new` | Submit a public repository URL and optional notification email. |
| `/ingestions/:ingestionId` | Ingestion status, evaluation action, and live evaluation progress. |
| `/ingestions/:ingestionId/report` | Persisted scorecard with dimension evidence and citations. |
| `/search` | Optional follow-up surface for ad-hoc semantic repository questions; can ship after the evaluation workflow. |

The ingestion and report IDs belong in the URL so refreshes, bookmarks, and recovery do not depend on in-memory React state.

### Key screens

1. **Repository work queue**
   - Page title and short explanation of what a scorecard contains.
   - One clear primary action to evaluate a repository.
   - Recent work as a compact table/list: repository, latest status, submitted time, overall score when available, and an open action.
   - Useful empty state with a repository URL field or a direct action to the submission screen.
   - No fabricated KPI tiles or placeholder recruiter metrics.

2. **Submit repository**
   - GitHub URL field with inline validation and an example URL.
   - Optional email field with a clear note that email arrives after report generation.
   - Submit button, duplicate/reused-ingestion explanation, and service error recovery.
   - On `202 Accepted`, navigate to the returned ingestion ID and immediately show the job state.

3. **Ingestion and evaluation progress**
   - A visible stage timeline for `QUEUED`, `INGESTING`, `COGNIFYING`, then evaluation stages (evidence retrieval, score generation, citation validation, report save).
   - Keep ingestion and evaluation progress visually distinct.
   - Show current stage/message, last update, and the repository being analyzed.
   - If the browser disconnects, reconnect to the job by ID and load its persisted state/report; disconnecting must not imply that server work was cancelled.
   - Clear failed state with the returned error and an appropriate retry path. Do not show a success state until persistence succeeded.

4. **Evaluation report**
   - Summary and overall score, with the equal-weight 0–5 rubric clearly explained.
   - Six dimensions: architecture, maintainability, testing, reliability, security, and performance/scalability.
   - For each dimension: score, rationale, and citations. Let a reviewer expand an evidence excerpt showing `filePath`, `startLine–endLine`, and excerpt text.
   - Use score bars/rings only as supporting visual cues; keep the numeric value, rationale, and evidence readable without relying on color.
   - Mark an insufficient-evidence dimension explicitly. Explain that line ranges are model-supplied and should be verified against source; the current API does not guarantee exact source offsets.
   - Provide a route back to the repository job and an affordance to start another evaluation when supported.

5. **Evidence search (follow-up)**
   - Repository selector/URL and natural-language query input.
   - Return evidence chunks with file paths and excerpts; do not invent a generated answer if the backend returns raw chunks only.
   - Keep this separate from the scorecard so a general query is not mistaken for an evaluation result.

## 5. React implementation approach

- **Framework:** React + TypeScript + Vite for a standalone client served locally during development.
- **Routing:** React Router, with route params as the durable source for ingestion/evaluation IDs.
- **Server state:** TanStack Query for reads, mutation state, retries, caching, and invalidation. Avoid mirroring server status in unrelated component state.
- **Forms:** React Hook Form with a small schema validator (Zod or equivalent), especially for URL/email validation and accessible inline errors.
- **Styling:** CSS custom properties for semantic color/type/spacing tokens. Use CSS Modules or a small utility layer; avoid introducing a large UI kit before the design language is established. Build standard buttons, text fields, tabs, status labels, dialogs, tables, and disclosure panels with semantic HTML and accessible keyboard behavior.
- **API boundary:** a typed `apiClient` module for base URL, JSON decoding, errors, and request cancellation; feature hooks such as `useCreateIngestion`, `useIngestionStatus`, and `useReport` consume that module. Keep response types aligned to the Java DTOs.
- **Local dev networking:** use a Vite `/api` proxy to `http://localhost:8080` so browser requests stay same-origin in development and do not require permissive CORS. For production, serve the React bundle behind the same origin or configure a specific allowed origin in Spring.
- **Secrets:** Gemini and Cognee credentials remain server-side. Never place provider keys in Vite variables or browser requests.

Suggested source layout:

```text
src/
  app/              router, providers, app shell
  components/       buttons, fields, status, tabs, citation panel
  features/
    repositories/   submission and work queue
    ingestions/     status and progress timeline
    evaluations/    stream client, report, dimension evidence
    search/         optional semantic evidence search
  lib/              api client, schemas, date/score formatting
  styles/           tokens and global styles
```

## 6. Existing API integration

The current Spring API already supports:

| Current endpoint | Frontend use |
| --- | --- |
| `POST /api/repositories/ingestions` | Submit `{ repositoryUrl, notificationEmail? }`; receive an ingestion status and ID. |
| `GET /api/repositories/ingestions/{id}` | Reconcile ingestion status after navigation or refresh. |
| `POST /api/repositories/ingestions/{id}/evaluations/stream` | Start or subscribe to evaluation and receive progress plus final report as SSE. |
| `POST /api/repositories/ingestions/{id}/evaluations` | Existing asynchronous JSON alternative. |
| `GET /api/repositories/evaluations/{id}` | Read evaluation job status when using the JSON alternative. |
| `GET /api/repositories/ingestions/{id}/report` | Load the persisted report. |
| `POST /api/repositories/search` | Retrieve semantic evidence for a natural-language repository query. |

The SSE route is a **POST**, so native `EventSource` is not suitable. Implement a small `fetch()` + `ReadableStream` parser that handles `event:` and `data:` frames, UTF-8 chunk boundaries, multiple events per read, error events, and stream closure. Treat the stream as progress delivery, not the durable source of truth: fetch job/report status after completion and on reconnection. Do not abort the server-side job when the component unmounts.

### Backend work needed before a durable work queue

There is no list endpoint for previous ingestion jobs in the current controller. Add a paginated endpoint such as `GET /api/repositories/ingestions?limit=20&cursor=...` returning the latest status and report summary; otherwise the home screen cannot restore repository history after browser storage is cleared or the user switches devices. Keep pagination and response shape consistent with the existing UUID-based job model.

The current scorecard endpoint is scoped by ingestion ID, while evaluation status uses evaluation ID. The UI should retain both IDs when received and use the report endpoint for persisted scorecard data. Define how an already-completed ingestion behaves when the client starts evaluation again before exposing that action as a repeatable control.

Before production, add authentication/authorization and ensure each reviewer can only access their own jobs and reports. The current MVP endpoints are local development endpoints and do not establish user identity or per-user access control.

## 7. Interaction and state requirements

Every route should cover the real states it can reach:

- Initial loading, skeleton where layout benefits, and empty state.
- Form validation, disabled submit, request in flight, accepted/queued, and duplicate job reuse.
- Ingestion stages and evaluation stage changes.
- Completed report and partial/insufficient evidence.
- Validation errors, missing IDs, backend unavailable, Gemini/Cognee failure, timeout, and malformed response.
- Stream disconnect, browser refresh, reconnect/reload, and recover persisted result.
- Email configured vs. not configured, with no claim of delivery until report generation completes; optional email should not block evaluation.
- Keyboard navigation, visible focus, labelled inputs, screen-reader status announcements, reduced motion, readable contrast, zoom, and touch-sized controls.

## 8. Delivery phases

1. **API readiness:** add ingestion history endpoint, settle repeat-evaluation behavior, document the SSE event envelope, and decide same-origin proxy/CORS deployment. Frontend should not hardcode assumptions about unavailable list behavior.
2. **React foundation:** scaffold Vite/TypeScript, routing, typed API client, semantic tokens, typography, app shell, and responsive navigation.
3. **Submission and job flow:** build repository form, create ingestion, status page, recovery/reconnect behavior, and progress timeline.
4. **Report experience:** parse/subscribe to SSE; load persisted report; build overall summary, six dimensions, evidence/citations, and empty/failure states.
5. **Work queue and search:** add recent ingestion list when API exists; then implement semantic search as a separate capability.
6. **Visual and accessibility QA:** inspect real renders at desktop `1440×900`, tablet `1024×768`, and mobile `390×844`; test long GitHub URLs, long file paths, citation excerpts, all statuses, and keyboard-only navigation.

## 9. Acceptance criteria

- A recruiter can submit a valid public GitHub URL and receives an identifiable job page on `202 Accepted`.
- The job view shows truthful ingestion/evaluation progress and recovers state after refresh or stream interruption.
- A completed report can be revisited from its route and displays all six score dimensions with citations and insufficient-evidence states.
- Errors remain attached to the correct ingestion/evaluation and can be recovered from; no failed operation appears completed.
- Email is optional and clearly described as post-report notification.
- The history screen loads from the API, not only local browser memory.
- The interface uses Fern-inspired hierarchy and product previews while remaining recognizably CampusCode, accessible, responsive, and free of invented metrics or unsupported actions.
- Evaluation copy explains the evidence boundary and requires human interpretation; it never recommends automatic hiring decisions.

## 10. Reference-use boundary

Use Fern to learn from its presentation, not to clone its website. Build original CampusCode branding and task-specific information architecture. Reuse neither Fern logos/assets nor copied text. If later implementation needs a concrete visual target for close matching, capture an approved CampusCode comp first; this plan does not claim pixel-level matching or authorize a frontend design beyond the direction described here.
