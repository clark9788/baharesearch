# Search Flow (Current Build)

This document describes the **actual runtime flow** of the app based on current code in:

- `ResearchService`
- `LocalCorpusSearchService`
- `GeminiClient`
- `ConfigLoader`

---

## 1) End-to-end runtime flow after clicking **Begin Research**

### UI entrypoint

1. `BahaiResearch.runResearch(...)` starts a background task.
2. It calls `ResearchService.conductResearch(topic, selectedAuthor, selectedTitle)`.

### Service orchestration (`ResearchService`)

1. Load config with `ConfigLoader.load()` (from `KEY_PATH`).
2. Initialize corpus dirs/schema with `CorpusBootstrapService.initializeIfEnabled(...)`.
3. Run ingest if configured via `CorpusIngestService.ingestIfConfigured(...)`.
   - Reingest occurs here when `corpus.forceReingest=true`.
4. Run local retrieval first:
   - `LocalCorpusSearchService.search(topic, selectedAuthor, selectedTitle, appConfig)`
5. Branch:
   - If local returns quotes → return local report.
   - If local returns no quotes:
     - `research.localOnlyMode=true` → return local “No Results”.
     - `research.localOnlyMode=false` → fallback to `GeminiClient.generateReport(...)`.

---

## 2) Local search pipeline (always first)

Inside `LocalCorpusSearchService.search(...)`, the flow is:

### A. Resolve query constraints

- Inputs considered:
  - Raw topic text
  - Optional explicit UI dropdown filters (`selectedAuthor`, `selectedTitle`)
  - AI intent hints from `GeminiClient.resolveLocalQueryIntent(...)`

- Effective constraints assembled:
  - `requiredAuthor` (explicit dropdown > manual topic inference)
  - `requestedBookTokens` (manual “from/in book”, dropdown title, optional AI title tokens)
  - `conceptTerms` (topic terms + AI concepts)

### B. Build FTS queries

- AND-style query: `toFtsQuery(...)` for precision
- OR-style query: `toFtsQueryOr(...)` as fallback
- Tokens are normalized; noise tokens and resolved author tokens are excluded from FTS terms.

### C. Candidate retrieval from SQLite

- Main query via FTS (`passages_fts MATCH ?`) joined with `passages` + `documents`.
- Optional exact SQL filters by author/title (if scoped).
- If AND query yields no rows, OR query is attempted.
- Optional phrase query (`knownPhrase`) is merged in.

### D. Filtering and ranking

- Author filter (exact canonical author match)
- Book-token filter
- Content-term filter
- Additional book-scoped fallback query if needed
- Boilerplate removal + deduplication
- Ranking by source priority + quality + BM25 score
  1. __Source priority__

  - A rule-based preference for some sources over others in specific cases.
  - In your current code, this mainly affects __Universal House of Justice__ queries: direct UHJ message sources are preferred, while compilations/secondary buckets are deprioritized.

  2. __Quality band__

  - A heuristic on quote length.
  - Medium-length passages (roughly ~200–900 chars) are treated as highest quality for display, then near-range, then very short/very long.

  3. __BM25 score__

  - Standard full-text relevance score from SQLite FTS.
  - It measures how well the passage text matches query terms (term frequency + rarity + field normalization).
  - In this implementation, sorting uses BM25 after the first two layers to break ties among similar-priority candidates.


### E. Semantic fallback (local still)

If candidate pool is empty but AI produced concepts:

- Re-query local corpus using concept-driven OR FTS terms
- Re-rank filtered local candidates again

### F. AI rerank of local candidates

- `GeminiClient.rerankLocalCandidates(...)` receives local candidates and returns `selectedIds`.
- Final quotes still come from local DB rows (`author/title/locator/url/text` from corpus).

---

## 3) AI handoff points in local-first mode

There are two AI calls during local retrieval:

1. **Intent resolution**: `resolveLocalQueryIntent(...)`
   - Input: topic + known work titles
   - Output JSON: `author`, `workTitle`, `knownPhrase`, `concepts[]`

2. **Candidate rerank**: `rerankLocalCandidates(...)`
   - Input: topic + local candidate list
   - Output JSON: `selectedIds[]`

So AI helps with interpretation/ranking, but citations are local unless full fallback is triggered.

---

## 4) Behavior by `research.localOnlyMode`

## `research.localOnlyMode=true`

- Local pipeline runs.
- If no local quotes found, app returns configured `research.noResultsText`.
- **No `generateReport(...)` fallback** to external quote synthesis.

## `research.localOnlyMode=false`

- Local pipeline still runs first.
- If local quotes exist, they are returned.
- If local quotes are empty, app calls `GeminiClient.generateReport(...)`.
  - This returns JSON summary + quotes (or exact `noResultsText`).
  - `enforceRequestedAuthor(...)` is applied to fallback output when topic specifies author.

---

## 5) Practical implications

- Local-first behavior is always active.
- `localOnlyMode` controls only **what happens after local zero-hit**.
- For deterministic/auditable results, keep `localOnlyMode=true`.
- For recall expansion when local misses, use `localOnlyMode=false`.

---

## 6) Debugging aids

Set `research.debugIntent=true` to see:

- `[IntentDebug]` (topic, ftsQuery, ai intent fields, resolved constraints)
- `[PipelineCount]` stage-by-stage candidate counts
- `[BoilerplateFilter]` removals and reasons

This is the fastest way to understand why a query returned specific quotes or no results.
