# BahaiResearch Deployment Guide

## Build a deployable JAR

From project root:

```cmd
mvn -DskipTests package
```

Output artifact:

```text
target/BahaiResearch-1.0.0-SNAPSHOT-all.jar
```

---

## Required config

The app reads runtime config from a properties file pointed to by `KEY_PATH`.

Example `keys.properties`:

```properties
gemini.apiKey=YOUR_API_KEY
gemini.model=gemini-2.5-flash
research.requiredSite=https://www.bahai.org/library/
research.localOnlyMode=true.
research.noResultsText=No Results
research.promptBoilerplate=Return quotes from primary Bahá’í scripture only. For each quote include author, book title, and paragraph or page number.
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
```

Notes:
- Corpus data is stored on disk under `corpus.basePath` and is **not bundled into the `-all.jar`**.
- On first run (with `corpus.autoInitialize=true`), the app creates the corpus directories, initializes SQLite schema, and writes a bootstrap manifest.
- With `research.localOnlyMode=true`, query-time answers come from local corpus only (no web lookup during query handling).
- Optional first-run ingest can be enabled with `corpus.autoIngestIfEmpty=true`.
- To rebuild corpus content without manually deleting the DB, set `corpus.forceReingest=true` for one run, then set it back to `false`.
- To get broader corpus coverage, increase `corpus.ingestMaxPages` (for example `1000` or `2000`).

---

## Run directly from command line

```cmd
set KEY_PATH=D:\path\to\keys.properties && java -jar D:\AI-Python\BahaiResearch\target\BahaiResearch-1.0.0-SNAPSHOT-all.jar
```

---

## One-command launcher script

You can also use `run-app.bat` (included in project root):

```cmd
run-app.bat C:\path\to\keys.properties
```

---

## Runtime package (source files required)

The curated xhtml source files must always be included in any deployment. They are required for the Source deep links to resolve correctly at runtime — the app serves them via a local HTTP server and opens the browser at the exact paragraph anchor.

Use `package-runtime-source-only.bat` to build the distribution package:

```cmd
package-runtime-source-only.bat
```

Optional: build private Java runtime first (so users do not need Java installed):

```cmd
build-runtime-image.bat
```

Then run the packaging command above. If `runtime\bin\java.exe` exists, it is included automatically.

Output package folder:

```text
dist\BahaiResearch-runtime-source-only\
```

It includes:
- `target\BahaiResearch-1.0.0-SNAPSHOT-all.jar`
- `run-app.bat`
- `data\corpus\curated\en\` (xhtml source files + manifest.csv)
- `bahai-research.properties` (local-only default profile)
- optional `runtime\` (private Java runtime, if built)

On first run the app builds `corpus.db` locally by ingesting from the curated source files. No prebuilt DB is required.

### Default behavior in packaged properties

- Works offline in local-only mode after first-run ingest
- No API key required for local corpus search
- AI assistance (intent parsing, reranking) can be enabled by adding `gemini.apiKey`

```properties
# Local-only mode: AI helps interpret the prompt and rank results but does not search the web
research.localOnlyMode=true
# Web fallback: AI may search bahai.org at query time — has produced hallucinations in the past
research.localOnlyMode=false
```

### Run packaged app

```cmd
run-app.bat C:\path\to\dist\BahaiResearch-runtime-source-only\bahai-research.properties
```

`run-app.bat` launch order:
1. Uses bundled `runtime\bin\java.exe` if present.
2. Otherwise falls back to system `java` on PATH.

This does **not** modify system `JAVA_HOME` or global `PATH`.

---

## Notes

- Build warnings from `maven-shade-plugin` about overlapping resources are expected for this JavaFX setup and do not prevent running.
- If Gemini requests time out on slower networks, increase `research.requestTimeoutSeconds` (for example, `120`).
