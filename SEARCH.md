# Search Algorithm

Documents the full-text search pipeline in `LocalCorpusSearchService.java`.

---

## Query Tiers — NEAR → AND → OR

Every search builds three FTS queries and tries them in order, stopping at the first that returns results.

### 1. NEAR (proximity, 2-token queries only)
Fires only when the topic produces **exactly 2 FTS tokens**.

```
NEAR(token1* token2*, 15)
```

Requires both terms to appear within 15 words of each other in the same passage.
This is the tightest possible match — use it when you want a passage that explicitly connects two concepts (e.g. "prayer unity").

### 2. AND (all terms required)
For 1–3 tokens: all required.
For 4+ tokens: first 3 required, remaining are OR'd together.

```
token1* AND token2* AND token3*
token1* AND token2* AND token3* AND (token4* OR token5*)
```

### 3. OR fallback
All tokens optional — any one match qualifies.
Triggered only when AND returns nothing.
Summary message includes a tip to try fewer keywords.

---

## Phrase / LIKE Search

After the FTS tier, a LIKE-based phrase pass runs to surface passages where the exact keyword sequence appears verbatim in the text.

**Skipped when NEAR fired.** A successful NEAR already guarantees tight term proximity — running LIKE on top would re-add passages where the terms appear far apart, defeating the NEAR constraint.

Two phrase passes (both skipped if NEAR fired):
1. **Topic phrase** — `%keyword1%keyword2%` against the raw query text
2. **AI known-phrase** — same LIKE pattern using a known scriptural phrase extracted by Gemini (supports long-text multi-word lookups)

Phrase hits receive a fixed score of `-99999` so they always sort ahead of BM25 FTS results.

---

## Author and Title Filtering

- **Author** — `lower(d.author) = lower(?)` exact match (not LIKE).
  Canonical values: `Baha'u'llah`, `Bab`, `'Abdu'l-Baha`, `Shoghi Effendi`, `Universal House of Justice`, `Compilation`.
  Author tokens are excluded from FTS queries (they're handled in SQL WHERE).

- **Title** — `lower(d.title) = lower(?)` exact match.
  Populated from DB dynamically when an author is selected in the UI — only shows titles that author has in the corpus.

- **AI author inference is suppressed.** Only the UI dropdown drives author filtering.
  Prevents AI from guessing "Baha'u'llah" from topic words like "revelation" and over-restricting results.

---

## Book Token Filtering

Explicit book scoping via `"from X"` / `"in book X"` patterns in the query text, or via the Title dropdown.
AI work-title inference is suppressed (would cause false restrictions like "unity" → Tabernacle of Unity).

---

## Content Term Filtering

Post-FTS filter: passage must contain at least one of the significant topic words (≥4 chars, non-noise).
AI-extracted concepts are merged in to catch synonym matches.

---

## Additional Book-Scoped Hits

When a Title filter is active and the main pipeline returns fewer results than requested, a second SQL pass fetches additional passages from that specific work without FTS constraints, filtered by content terms.

---

## Semantic Fallback

Triggered when `candidatePool` is empty after the full pipeline.

Uses AI-extracted concepts (e.g. "love", "compassion", "unity") as OR FTS terms instead of the raw query text.
Handles queries with modern words not in the corpus (e.g. "empathy") by mapping them to scriptural equivalents.

NEAR is not used in semantic fallback — concepts are passed directly as OR queries.

---

## Ranking

Results are sorted before the final `requestedQuotes` limit is applied:

1. **Phrase hits** (score ≤ −99990) always first — sorted among themselves by passage length ascending (shortest = most precise match).
2. **Source priority** — for Universal House of Justice queries, official Messages rank above compilations.
3. **Quality band** — passages 200–900 chars (band 0) beat 120–1100 chars (band 1) beat everything else (band 2).
4. **BM25 score** — within the same band, lower (more negative) BM25 = better relevance.

---

## Boilerplate Filter

Passages removed before ranking:
- Over 15,000 characters (entire-book ingest accident)
- Contains "bahai reference library" (site header)
- Starts with "a collection of" / "a selection of" (section header)
- Contains "can be found here" (navigation link)
- Navigation elements: "read online", "bahai org home", "copyright and terms of use", etc.
- Contains "see also"

---

## AI Reranking

After ranking, the top `requestedQuotes × 6` candidates (max 20) are sent to Gemini for final selection.
If the API call fails or returns nothing, the pipeline falls back to the top N by score.

`research.localOnlyMode=false` is required for Gemini reranking to run (default: true = local only).

---

## Debug Logging

Set `research.debugIntent=true` in the properties file to enable pipeline logging:

- `[IntentDebug]` — full intent parse, query, filters, concepts
- `[PipelineCount]` — hit counts at each stage (NEAR, AND, OR, phrase, book, content, candidatePool)
- `[BoilerplateFilter]` — each removed passage with reason + 120-char snippet
