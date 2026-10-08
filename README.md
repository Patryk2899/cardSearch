# CardSearch

A Spring Boot backend and a responsive frontend built with HTML, CSS, and
JavaScript. Search fictional sample cards, filter by type, sort by name or rarity,
and clear filters when no results match.

The backend stores real card records in SQLite and imports the supplied Scryfall
JSONL file on startup. The frontend still uses an in-memory sample collection in
`frontend/app.js`; a card-search API has not been implemented yet.

## Run with Docker

Install Docker with Compose, then run from the repository root:

```sh
docker compose up --build
```

- Frontend: http://localhost:3000
- Backend: http://localhost:8080 (no root endpoint is implemented yet)

Both images build from source; a local Java or Node installation is unnecessary.
No separate database server or `.env` file is required. Nginx serves the frontend and forwards
`/api/` requests to the backend, ready for future API endpoints.

Optional environment variables (in your shell or a local `.env` file):

```dotenv
FRONTEND_PORT=3000
BACKEND_PORT=8080
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0
```

Stop the services with `docker compose down`.

## SQLite and initial data

Place the seed file at:

```text
tmp/cardJsonData/default-cards-20261008090544.jsonl
```

The backend creates `data/cards.db` locally. Docker Compose mounts the seed
directory read-only and stores `/app/data/cards.db` in the persistent
`sqlite-data` volume. `docker compose down` preserves the database. The large
source file and generated local database are ignored by Git and excluded from
Docker image builds.

The `cards` table contains one row per Scryfall card ID, with name, oracle ID,
language, set code/name, collector number, type line, rarity, mana cost/value,
oracle text, image URL, and Scryfall URL. `raw_json` preserves the complete source
record, including faces, prices, legalities, and other fields. Name, set code, and
oracle ID are indexed.

Import reads one line at a time and inserts in batches within one transaction.
If a line is invalid, the import rolls back and startup fails with its line
number. Correct the source and restart to retry. Existing card IDs are preserved
without duplicates. A completed source filename is recorded in `seed_imports`
and skipped on subsequent starts; changed seed data should use a new filename.
First startup takes longer while the roughly 634 MB file is imported.

Local configuration:

```dotenv
CARDS_DATABASE_PATH=data/cards.db
CARDS_IMPORT_PATH=tmp/cardJsonData/default-cards-20261008090544.jsonl
CARDS_IMPORT_ENABLED=true
```

Docker Compose fixes the database and seed paths to the mounted directories;
`CARDS_IMPORT_ENABLED=false` disables seeding while still creating the schema.
The seed directory must exist for Compose's bind mount, even when seeding is
disabled. A missing source file fails startup unless that filename was already
imported or seeding is disabled.

To initialize the local database without running the web server (after building):

```sh
./gradlew bootJar
java -jar build/libs/cardSearch.jar --spring.main.web-application-type=none
```

Inspect it with a SQLite client:

```sql
SELECT COUNT(*) FROM cards;
SELECT id, name, set_code, rarity FROM cards LIMIT 10;
SELECT * FROM seed_imports;
```

## Local development

The backend requires JDK 25:

```sh
./gradlew bootRun
```

On Windows, use `.\gradlew.bat bootRun` instead. Run backend tests with
`./gradlew test` (or `.\gradlew.bat test` on Windows).

The frontend needs no build step or dependencies. Open `frontend/index.html` in
a browser, or serve the directory with any static HTTP server, for example:

```sh
python -m http.server 3000 --directory frontend
```

The `/api/` proxy is available when running through Docker Compose. To connect
real data later, implement a backend endpoint under `/api/` and replace the sample
array in `frontend/app.js` with a fetch to that endpoint.
