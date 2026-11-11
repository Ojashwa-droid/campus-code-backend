# CampusCode React client

Standalone React, TypeScript, and Vite client for the local CampusCode API.

## Run locally

Start the Spring API at `http://localhost:8080`, then run:

```powershell
npm install
npm run dev
```

Vite prints the local URL. Its `/api` proxy forwards requests to the Spring API. The first iteration includes repository submission, ingestion status, streamed evaluation progress, and persisted scorecards. It does not include a history list or evidence search.

## Commands

- `npm run dev` — start the development server.
- `npm run build` — type-check and create a production bundle in `dist/`.
- `npm run preview` — preview the production bundle locally.

The visual reference for the submission screen is in [../design/submission-target.png](../design/submission-target.png).
