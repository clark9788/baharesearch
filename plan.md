# BahaiResearch Plan (Local Corpus Revision)

## Vision
Build a focused Bahá’í research app where:
- The **local corpus** is the only source of truth at query time
- AI helps interpret user intent and summarize verified passages
- Quotes and citations are deterministic, auditable, and reproducible
- If content cannot be verified locally, the app returns `No Results`

---

## Direction Update: Local Corpus First + Verification First

### Why this change
Current web-constrained prompting still allows inaccuracies in quote text and references. Even with strict `site:` instructions, an LLM can produce plausible but incorrect citations.

### New principle
Use AI for:
1. **Natural-language understanding** (free-form text -> structured query)
2. **Synthesis** (summarize already-verified local passages)

Do **not** use AI as the final authority for quote text or references.

### Explicit runtime rule
- **Do not look on the web during user queries.**
- Query-time retrieval must use local corpus only.
- If requested material is not found/verified locally, return exactly: `No Results`.

---

## Product Goals
1. User enters free-form topic/request naturally.
2. System maps request to structured constraints (topic, author/body, work filters).
3. Local retrieval returns candidate passages from trusted stored texts.
4. Deterministic verification confirms exact quote text and citation fields.
5. Final answer is generated from verified passages only.
6. If validation fails at any step, fail closed to `No Results`.

---

## Target Architecture

### 1) UI Layer (JavaFX) — Keep
- Input: research topic/request
- Output: structured summary and quote cards
- Optional: diagnostics (why a quote was rejected)

### 2) Orchestration Layer (`ResearchService`) — Keep and Refactor Internals
- Keep service boundary and request/response flow
- Replace web-leaning quote generation path with local retrieval pipeline

### 3) Local Corpus Layer — New Core
- Local storage for documents/passages and canonical metadata:
  - author
  - work/book title
  - paragraph/page/section
  - canonical bahai.org URL
  - corpus version / ingest timestamp
- Local index for fast lookup (FTS first, vector optional later)

### 4) Query Interpretation Layer (LLM-Assisted) — Refined
- Convert free-form user text into structured query object
- Enforce controlled schema for query intent
- No direct quote authority granted to the LLM

### 5) Deterministic Verification Layer — New Core
- Exact-match quote check against local stored text
- Citation fields must be present in local metadata
- Reject partial/ambiguous citations
- Enforce author/body filters before final output

### 6) Response Assembly Layer — Keep and Strengthen
- Use existing output models (`ResearchReport`, `QuoteResult`)
- Output only verified quotes with complete citations
- Fall back to `No Results` if no compliant results remain

---

## Query-Time Flow (Authoritative)
1. User enters free-form text.
2. LLM/parser produces structured query constraints.
3. Local retriever returns candidate passages.
4. Quote selector chooses candidate excerpts.
5. Verifier confirms exact text and citation completeness.
6. Final response generated strictly from verified local passages.
7. If none pass checks -> `No Results`.

---

## Corpus Update Flow (Separate from Query-Time)
- Corpus ingestion/sync from `bahai.org/library` runs as a separate process (manual or scheduled).
- Query requests never trigger live web retrieval.
- Corpus versioning supports reproducibility and auditing.

---

## Salvage vs Rewrite Assessment
This is a **backend pivot**, not a full rewrite.

### Reusable now
- UI and interaction flow
- Config framework (`AppConfig`, `ConfigLoader`)
- Service entry points (`ResearchService`)
- Data models (`ResearchReport`, `QuoteResult`)
- Structured-output and guardrail discipline

### Needs replacement/major additions
- Current quote acquisition logic that depends on model-generated web-grounded citations
- New local corpus schema and indexing
- New deterministic quote/citation verifier
- New local retrieval pipeline

Expected outcome: substantial reuse of project structure while replacing the reliability-critical quote retrieval core.

---

## Implementation Phases

### Phase 1: Local Corpus Foundation
- Define storage schema for documents, passages, and citation metadata
- Implement ingest/sync process from `bahai.org/library`
- Normalize text + preserve canonical URLs and provenance

### Phase 2: Local Retrieval + Verification
- Implement local search (keyword/FTS)
- Add quote extraction from retrieved passages
- Implement exact-match verification and citation completeness checks
- Fail closed to `No Results` on any verification failure

### Phase 3: LLM Grounded Synthesis
- Restrict LLM context to verified passages only
- Use LLM for summarization/response composition only
- Preserve strict schema output

### Phase 4: Hardening and UX
- Add rejection diagnostics and transparency indicators
- Add corpus status/version visibility in UI
- Add regression tests for known authoritative quotations

---

## Success Criteria (Updated)
- 100% of returned quotes exact-match local stored text
- 100% of returned citations come from local metadata fields
- No query-time dependency on external web search
- Deterministic outputs for same query + corpus version
- Clean, maintainable migration path from current codebase
