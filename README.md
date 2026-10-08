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
