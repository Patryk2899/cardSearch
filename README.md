# CardSearch

A Spring Boot backend and a responsive frontend built with HTML, CSS, and
JavaScript. Paste and save card lists to SQLite, then reopen them from the UI.

The backend stores real card records in SQLite and imports the supplied Scryfall
JSONL file on startup. The frontend manages saved lists; a card-search API has
not been implemented yet.

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

### Saved card lists

In the frontend, click **New list**, paste a list into **Your card lists**,
and click **Add list**. Expand a saved list and use **Remove list** to delete it.
Use **Edit list** to load an existing list into the form, change its name or
contents, and click **Save changes**. This updates the same list without creating
a duplicate. **Cancel edit** discards the draft. Failed updates keep your changes
in the form so you can retry. Quantity totals are recalculated after each edit.
The name is optional. There is no card-count or text-length validation; lists
with more than 100 cards are supported. Quantities, duplicates, unknown card
names, blank lines, and original formatting are preserved exactly. Only empty
input is rejected. The UI shows the total number of cards as well as the number
of entries. `2 Mountain (FRA) 393` is parsed as quantity 2, card name `Mountain`,
set code `FRA`, and collector number `393`. A line without a quantity means one
copy; `2x Mountain` is also supported. Quantities have no application-defined
upper limit. Blank lines are ignored when counting entries.
The card name ends at the first `(` and surrounding whitespace is trimmed.
For image lookup, face separators such as `Bilbo Baggins, Burglar / Take a Glance`
are normalized to `Bilbo Baggins, Burglar // Take a Glance`. Original pasted text
is preserved, and existing `//` separators remain valid.

Saved lists can be expanded and read again after refreshing the page. They are
stored in the `card_lists` SQLite table in the same persistent database as the
card catalog. Structured entries and totals are derived from the saved text when
reading, so existing lists also recognize quantities without a database migration.
### Image selection for each copy

Expand a saved list and click **Choose images**. Click a card image in the grid to
choose an image, then move between cards with **Previous card** and **Next card**.
Quantities are handled as individual copies: `2 Mountain (FRA) 393` produces two
separate choices, and the copies may use different printings.

All matching printings in the imported catalog are offered, including available
images whose catalog names contain the parsed card name (case-insensitive), and
front/back images for multi-face cards. Each printing uses one display-size image;
different resolutions of the same image are not separate choices. The set and
collector number from the pasted list are prioritized without hiding other images.
Image previews load from the image URLs in the catalog. A card with no available
image is flagged so you can correct the list or update the catalog.
Contains matching scans a compact covering index (`name`, `oracle_id`, `id`)
and fetches full JSON only for matching cards, avoiding a full catalog-table
scan for each distinct name. Existing databases receive the index on startup.

Click **Save images to list** above the image grid to save the assigned images,
even when some copies still need images. Reopen the list to resume later.
The backend validates the submitted choices and saves them atomically in `list_card_images`.
Each row stores the list ID, entry index, copy index (both zero-based), catalog
card ID, face index (`-1` for a whole-card image), and image URL. Reopening the
picker restores saved choices. There is no application-defined card-count limit;
copies are reviewed one at a time rather than rendering the whole list at once.
Each saved list shows `Images: assigned / total`, marked **Not started**,
**In progress**, or **Complete**. Counts reflect saved assignments, count each
copy separately, and refresh after saving images. List summaries expose this
count as `assignedImageCount`.

Renaming a list preserves its image choices. Changing the card-list text clears
old assignments, and deleting a list removes its assignments. If a list changes
while its images are being chosen, saving returns a conflict and asks you to
reopen the picker. Failed image saves retain the current choices for retry.

API endpoints:

- `POST /api/card-lists` — JSON: `{"name":"My deck","cardsText":"1 Sol Ring\n10 Forest"}`.
- `GET /api/card-lists` — saved-list summaries, newest first.
- `GET /api/card-lists/{id}` — full saved text and metadata.
- `PUT /api/card-lists/{id}` — replace name/text using the same JSON as POST;
  preserves the list ID and creation date (404 if it does not exist).
- `DELETE /api/card-lists/{id}` — delete one list (204; 404 if it does not exist).
- `GET /api/card-lists/{id}/image-options` — entries, available images, and saved choices.
- `PUT /api/card-lists/{id}/image-selections` — replace saved per-copy choices (partial selections supported) with the
  original `cardsText` and `selections` array (`entryIndex`, decimal-string
  `copyIndex`, and `optionId` from the image-options response).

List summaries include `cardCount`. Full-list responses additionally include
`entries`, with `quantity`, `cardName`, `setCode`, and `collectorNumber`.

Run the frontend through Docker Compose to use the API proxy and saved-list features.

```sql
SELECT id, name, line_count, created_at FROM card_lists;
SELECT cards_text FROM card_lists WHERE id = 'your-list-id';
SELECT * FROM list_card_images WHERE list_id = 'your-list-id';
```

### Generate printable PDFs

Save an image for every card copy, then click **Generate PDFs** on the saved list.
The app generates the files in the background and shows progress. Click
**Download PDFs (ZIP)** when ready. Each PDF holds up to nine cards in list order,
including repeated copies; the last PDF leaves unused positions blank.
For example, 100 cards produce 12 separate PDFs. Unsaved image choices are not
included. A running export uses a snapshot of the saved list at the time it starts.

The bundled `src/main/resources/pdf/nine-cards.sla` is the supplied Scribus 1.6.2
template. Scribus renders it directly, preserving its original, slightly
asymmetric frame coordinates, 181.5 × 252 point image frames, non-proportional
image fitting, A4 trim area, PDF 1.4 RGB output, 300 DPI export setting, and
40-point PDF bleed on every side. The resulting PDF MediaBox is 675.28 × 921.89
points with a 595.28 × 841.89 point A4 TrimBox. The template's old temporary image
references are replaced only in generated working copies. The source stays intact.

The template's PDF export settings have mirroring disabled; its separate Scribus
printer settings enable vertical mirroring. Exports use the saved PDF settings.
Printer-driver options (paper feed, printer scaling, paper type, and similar
device settings) still need to be selected when printing the downloaded PDFs.
The app does not send jobs directly to the printer.

Docker installs Scribus and a virtual display automatically. For local Java
development, install Scribus and set `CARDS_PDF_SCRIBUS_COMMAND` to its executable
if it is not on PATH (for example `C:/Program Files/Scribus 1.6.0/Scribus.exe`).
Linux also needs `xvfb-run` and `xauth`. Generated files, job status, and downloaded
image cache are stored beside the SQLite database in `pdf-exports/` (Docker:
`data/docker/pdf-exports/`). Finished exports remain downloadable after a backend
restart; interrupted exports can be generated again. Repeated image URLs are
downloaded once and cached for later exports.

API: `POST /api/card-lists/{id}/pdf-exports` starts a job (202),
`GET /api/card-lists/{id}/pdf-exports/{jobId}` reports status, and
`GET /api/card-lists/{id}/pdf-exports/{jobId}/download` downloads the ZIP.
Incomplete lists return 409. Rendering errors are reported in the job status;
technical details are retained in the job's `scribus.log`.

Template regression checks: `python -m unittest discover -s src/test/python`.

### Card catalog seed

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

The frontend needs no build step or dependencies. Run it through Docker Compose
so that Nginx forwards `/api/` requests to the backend. For other local setups,
configure your static server to proxy `/api/` to `http://localhost:8080`.

Run frontend behavior checks with Node.js:

```sh
node --test frontend/tests/image-picker.test.cjs
```
