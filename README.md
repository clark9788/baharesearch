# BahaiResearch

Desktop research assistant for finding sourced Bahá’í quotes from a **local authenticated corpus** (with optional AI-assisted intent/reranking).

## What this project does

- Uses a local SQLite corpus for quote retrieval
- Supports curated source ingest (DOCX/HTML/PDF) from `data/corpus/curated/en`
- Returns structured results (quote, author, book, locator/page, URL)
- Can run in local-only mode (no web lookup at query time)

---

## Tech stack

- Java 21
- JavaFX
- SQLite
- Maven
- Optional Gemini API integration for intent/reranking

---

## Build

From project root:

```cmd
mvn -DskipTests package
```

Output:

```text
target/BahaiResearch-1.0.0-SNAPSHOT-all.jar
```

---

## Runtime configuration (`KEY_PATH`)

The app reads settings from a properties file pointed to by environment variable `KEY_PATH`.

Create a local file like `bahai-research.example.properties` (do **not** commit real secrets):

```properties
gemini.apiKey=YOUR_API_KEY
gemini.model=gemini-2.5-flash

research.requiredSite=https://www.bahai.org/library/
research.localOnlyMode=true
research.debugIntent=false
research.noResultsText=No Results
research.maxQuotes=8
research.requestTimeoutSeconds=90

corpus.basePath=data/corpus
corpus.databaseFileName=corpus.db
corpus.snapshotsDirName=snapshots
corpus.ingestDirName=ingest
corpus.autoInitialize=true

corpus.sourceBaseUrl=https://www.bahai.org
corpus.ingestSeedUrl=https://www.bahai.org/library/
corpus.ingestMaxPages=5000
corpus.ingestRequestDelayMillis=150
corpus.minPassageLength=160

corpus.autoIngestIfEmpty=false
corpus.forceReingest=false

corpus.curatedIngestEnabled=true
corpus.curated.baseDir=curated/en
corpus.curated.manifestFileName=manifest.csv
```

---

## Run

### Option A: direct command

```cmd
set KEY_PATH=D:\path\to\bahai-research.properties && java -jar target\BahaiResearch-1.0.0-SNAPSHOT-all.jar
```

### Option B: helper script

```cmd
run-app.bat D:\path\to\bahai-research.properties
```

---

## Packaging recommendations

For end-user runtime package, include:

- `target/BahaiResearch-1.0.0-SNAPSHOT-all.jar`
- `data/corpus/corpus.db` (and `-wal`/`-shm` if present while app is open)
- your local properties file (distributed privately)
- optional `run-app.bat`

Recommended runtime flags:

- `corpus.autoIngestIfEmpty=false`
- `corpus.forceReingest=false`
- `research.localOnlyMode=true`

Keep curated source files in repo under:

- `data/corpus/curated/en/**`

---

## Git notes

- `.gitignore` excludes build output, runtime DB artifacts, ingest/snapshot outputs, and `*.properties`
- Keep secrets out of Git (especially API keys)

Typical workflow:

```cmd
git add .
git commit -m "Describe change"
git push
```

---

## Useful docs

- `DEPLOYMENT.md` – packaging and run details
- `src/main/java/com/bahairesearch/config/Search_flow.md` – query/search flow notes
