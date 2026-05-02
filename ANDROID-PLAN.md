# BahaiResearch Android Port — Planning Notes

## Context
This is a port of the Windows JavaFX desktop app (D:/AI-Python/BahaiResearch) to Android.
The Windows app searches a local SQLite corpus of ~22,000 passages from authoritative Bahá'í texts
using FTS5 full-text search. A Gemini AI layer exists but is effectively unused (localOnlyMode=true).

## Key Decisions Made

### Language & UI
- **Java** (not Kotlin) — existing codebase is Java, no iOS target so no Kotlin Multiplatform benefit
- **XML layouts + standard Android Views** (not Jetpack Compose) — Compose is Kotlin-first, awkward from Java
- Simple UI: author dropdown, title dropdown, query field, search button, scrollable results list with source links

### AI / Gemini — STRIP IT
- `localOnlyMode=true` in the Windows app — AI path has never been used in practice
- Storing a Gemini API key in an APK is a security risk (APKs are trivially decompiled)
- User has learned effective keyword query craft — AI reranking adds complexity without benefit
- Remove all Gemini/AI code from the Android version entirely

### Distribution
- GitHub Releases (APK) — no Play Store, no $25 fee
- Users enable "install from unknown sources" once — acceptable for a niche scholarly tool

### Project Structure
- Separate GitHub repo (not a monorepo with the Windows app)
- Android Studio owns the project root / Gradle structure
- Use Android Studio's bundled Gradle wrapper (`gradlew`) — do NOT use a global Gradle install

---

## Files to Copy from Windows Project

From `src/main/java/com/bahairesearch/`:

| File | Action |
|---|---|
| `corpus/LocalCorpusSearchService.java` | Copy, remove AI methods (resolveLocalQueryIntent, rerankLocalCandidates) |
| `corpus/CorpusIngestService.java` | Copy for DB schema reference; Android won't re-ingest from xhtml |
| `model/` (all files) | Copy as-is |
| `config/AppConfig.java` | Copy, adapt (no .properties file on Android) |
| `config/ConfigLoader.java` | Adapt or replace — Android uses SharedPreferences or hardcoded defaults |
| `research/ResearchService.java` | Copy, gut the AI fallback path |

**Do NOT copy:**
- `BahaiResearch.java` (JavaFX UI — full rewrite needed)
- `BahaiResearchLauncher.java`
- `ai/` folder
- `web/` folder
- Anything referencing `GeminiClient`

---

## Corpus Database

The Windows app builds a SQLite DB from xhtml source files (in `data/corpus/curated/en/html/`).
The Android app should ship with a **pre-built copy of that DB**.

- Copy the built DB file into the Android project under `app/src/main/assets/corpus.db`
- On first launch, copy it from `assets/` to the app's internal storage (`context.getDatabasePath(...)`)
- After that, open it read-only from internal storage using Android's SQLite API
- The DB uses FTS5 — Android's bundled SQLite supports FTS5 (verify on emulator early)

The Windows DB location: `data/corpus/corpus.db`

## Source xhtml Files

The anchor IDs stored in the DB (passages.locator) point into the local xhtml files.
Copy `data/corpus/curated/en/html/` (19MB, 89 files) into `app/src/main/assets/html/`.
Open in a WebView using: `file:///android_asset/html/filename.xhtml#anchorId`

Alternative: the same anchor IDs work on live bahai.org pages — could link online instead.

---

## Source Links / Deep Links

The Windows app constructs deep links using:
- `documents.canonical_url` (local file:// URI to the xhtml file)
- `passages.locator` (numeric anchor ID from the xhtml, e.g. `#990539395`)

On Android, `file://` URIs to assets won't work the same way.
Options:
1. Open the bahai.org online URL instead (construct from known URL pattern)
2. Bundle the xhtml files as assets and use a WebView with `file:///android_asset/...#anchorId`
3. For now, just show the passage text without a clickable link — simplest starting point

Decide this when you get to the results UI.

---

## Dependencies (Gradle equivalents)

```gradle
dependencies {
    // SQLite is built into Android — no xerial needed
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'       // if keeping any network calls
    implementation 'com.fasterxml.jackson.core:jackson-databind:2.18.2'
    // jsoup only needed if parsing xhtml — probably not needed in Android version
}
```

---

## Search Logic Notes (from Windows app memory)

- FTS5 AND query: 1–3 terms all required; 4+ terms: first 3 AND, rest OR
- OR fallback inside `findHits()` when AND returns empty
- Author filter: `lower(d.author) = lower(?)` exact match (not LIKE)
- Canonical author values: `"Baha'u'llah"`, `"Bab"`, `"'Abdu'l-Baha"`, `"Shoghi Effendi"`, `"Universal House of Justice"`, `"Compilation"`
- Semantic fallback triggers when candidatePool is empty after main pipeline
- Boilerplate filter removes passages > 15,000 chars and nav/header junk

## Starting Point Suggestion

1. Create Android Studio project (Empty Activity, Java, min SDK 26+)
2. Get the DB copied-on-first-run working and a raw SQL query returning results
3. Build the UI around that working search
4. Wire up author/title dropdowns from the manifest (or query distinct values from DB)
