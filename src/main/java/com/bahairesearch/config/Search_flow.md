Great question. Here’s the __actual runtime flow__ after you click __Begin Research__ in this build:

1. __UI entrypoint__

- `BahaiResearch.runResearch(...)` starts a background task.
- It calls `ResearchService.conductResearch(topic)`.

2. __Startup + corpus readiness__ (`ResearchService`)

- Loads config: `ConfigLoader.load()`
- Ensures corpus schema/dirs: `CorpusBootstrapService.initializeIfEnabled(...)`
- Runs ingest if configured: `CorpusIngestService.ingestIfConfigured(...)`
  - (This is where your reingest happens when `forceReingest=true`.)

3. __Primary retrieval path = Local corpus search__

- `LocalCorpusSearchService.search(topic, appConfig)` runs first.
- If it returns quotes, app uses that result and stops (no web AI quote generation step).
- If no local quotes and `localOnlyMode=false`, it falls back to `GeminiClient.generateReport(...)`.

---

## How the local query is formed

Inside `LocalCorpusSearchService.search(...)`:

A) __FTS query from user text__

- `toFtsQuery(topic)` tokenizes input.
- Keeps tokens length >=3 and removes noise terms (`by`, `for`, `the`, etc.).
- Adds wildcard `*` per token and joins with `OR`.
- Example shape: `unity* OR consultation* OR justice*`

B) __Author / title / concept constraints__

- Manual inference from topic (e.g., UHJ, Baha'u'llah, 'Abdu'l-Baha)

- Plus AI intent hints (see next section) merged into:

  - `requiredAuthor`
  - `requestedBookTokens`
  - `conceptTerms`

C) __DB retrieval__

- Main SQL uses FTS table (`passages_fts MATCH ?`) joined to `passages` + `documents`.
- Applies author `LIKE` when inferred.
- Pulls a larger candidate pool than final quote count.

D) __Post-filtering / ranking__

- Filter by requested author/book/content terms.
- Optional extra fallback query for book-scoped results.
- Remove boilerplate and duplicate quote text.
- Rank candidates.

E) __AI rerank (local candidates only)__

- Sends the bounded candidate list to AI and asks it to return selected candidate IDs.
- Final displayed quotes are still from your local DB rows.

---

## Where AI handoff happens (and what is sent)

There are __two AI handoffs__ in the local-first flow:

### 1) Intent resolution handoff

Method: `GeminiClient.resolveLocalQueryIntent(...)`

__Sent to AI:__

- User query text

- Up to ~150 known local work titles

- Instruction to return JSON:

  - `author`
  - `workTitle`
  - `knownPhrase`
  - `concepts[]`

__Returned from AI:__

- Structured intent object used to tighten local retrieval.

### 2) Candidate rerank handoff

Method: `GeminiClient.rerankLocalCandidates(...)`

__Sent to AI:__

- Original user query

- Candidate list from local DB, each with:

  - ID (1-based index in candidate list)
  - author, title, locator, URL, quote text

- Instruction: return JSON `{"selectedIds": [...]}`

__Returned from AI:__

- A list of selected candidate IDs.
- App maps those IDs back to local candidates.

So AI is guiding __interpretation + ranking__, but quote text/citation ultimately comes from local corpus rows.

---

## Full AI fallback path (only if local returns nothing)

Method: `GeminiClient.generateReport(...)`

__Sent to AI:__

- Topic
- Prompt constraints (site, schema, max quotes, required fields)

__Expected back:__

- JSON with `summary` and quote objects (`quote/author/bookTitle/paragraphOrPage/sourceUrl`)
- Or exact `noResultsText`

This is the path that can reintroduce external variability, but it only triggers when local search yields no quotes (unless you set local-only mode to force no fallback).
