# assistant-api

Backend for the personal assistant (items, tags, calendar, quick add). Kotlin, Spring Boot 4 WebFlux with
coroutines, Postgres over R2DBC, Flyway migrations. LLM calls go through [Koog](https://github.com/JetBrains/koog).

Design requirements live in `../docs/requirements.md`.

## Run locally

```sh
./gradlew bootTestRun
```

Starts an embedded Postgres (data in `.devdb/`) and the API on http://localhost:8080.
Swagger UI: http://localhost:8080/swagger-ui.html

To enable quick add, set `LLM_API_KEY` (and optionally `LLM_MODEL`, default `gpt-6-luna`).

## Test

```sh
./gradlew test
```

Tests run against an embedded Postgres (no Docker needed) and also regenerate `openapi.json`,
the API contract that clients are generated from. Commit it when it changes.

## Configuration

| Env var | Default | |
|---|---|---|
| `DB_R2DBC_URL` / `DB_JDBC_URL` | `localhost:5432/assistant` | Same database, two drivers (Flyway uses JDBC) |
| `DB_USERNAME` / `DB_PASSWORD` | `assistant` | |
| `LLM_API_KEY` | | Quick add falls back to a plain draft without it |
| `LLM_MODEL` | `gpt-6-luna` | |
| `LLM_BASE_URL` | `https://api.openai.com` | |

The API has no login of its own. It is only reachable inside the cluster and through the
Tailscale ingress, so the tailnet is the access boundary.

## API overview (`/api/v1`)

Everything is an **item**: optional `by` (deadline), optional `at` (scheduled time), optional parent, tags.

| | |
|---|---|
| `GET/POST /items`, `GET/PATCH/DELETE /items/{id}` | CRUD. PATCH is a JSON merge patch; send `If-Match: "<version>"` for optimistic locking (412 on conflict) |
| `GET /items/{id}/descendants` | All sub-items, flattened |
| `POST /items/{id}/restore` | Undo a delete (restores the subtree) |
| `GET/POST /tags`, `PATCH/DELETE /tags/{id}` | Tags with colors |
| `GET /calendar?from=&to=&tags=&sessions=` | Ready-to-draw entries: `block`, `allDayBlock`, `deadline`, `allDayDeadline` |
| `POST /quick-add/parse` | Free text → proposed item (not saved) |
| `GET /changes?since=` | Sync feed for offline clients, including deletions |

Rules: completing an item completes its descendants; deleting deletes its subtree; an item is overdue when its
`by` has passed and it isn't done; time spent = timed descendants that are done or over.
