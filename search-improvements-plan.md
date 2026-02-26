# Baha'i Search Quality Improvement Plan

## Context

This plan addresses search quality issues in the Baha'i research application that searches a local SQLite corpus of 76 documents (22,428 passages) from authoritative Baha'i texts.

This plan aligns with the project's "Local Corpus First" philosophy (see `plan.md`) - ensuring deterministic, auditable results from the local corpus without web dependencies.

### Current Problems

1. **OR-only search logic**: All query terms are joined with OR (line 813), causing results with ANY term to appear, not necessarily the most relevant passages
2. **Author/title pollution**: Author and title words from queries get included in the FTS search terms, reducing relevance
3. **Bab/Baha'u'llah confusion**: Author truncation returns "bah" (line 757) which matches both "Baha'u'llah" AND "Bab" via SQL LIKE query
4. **Unnecessary complexity**: Extensive code handling author name variations when exact names exist in the manifest
5. **Lack of structured input**: No way for users to explicitly specify author/title filters, forcing reliance on AI parsing

## Proposed Solution: Hybrid Approach

The solution combines **structured UI fields** (for precise searches) with **AI fallback** (for natural language/paragraph queries).

### User Experience

**Use Case 1: Structured Search (Fast & Precise)**
```
Author dropdown: [Baha'u'llah selected]
Title dropdown: [All Titles]
Search query: "unity and justice"
```
→ Direct SQL filter + simple FTS, no AI parsing needed

**Use Case 2: Free-form/Paragraph Search (Intelligent)**
```
Author dropdown: [All Authors]
Title dropdown: [All Titles]
Search query: "I'm writing an essay about how communities can build unity
               through justice. What do the Baha'i writings say about this?"
```
→ AI parses intent → Extracts concepts → Search with AND logic → AI ranks results

**Use Case 3: Compilations**
```
Author dropdown: [Compilation selected]
Search query: "steadfastness"
```
→ Filters to compilation documents only (mixed authorship)

### Implementation Components

## 1. Add JavaFX UI Fields (NEW - HIGHEST PRIORITY)

**Add to search UI**:
- **Author dropdown**: ComboBox with options:
  - "All Authors" (default)
  - "Baha'u'llah"
  - "'Abdu'l-Baha"
  - "Bab"
  - "Shoghi Effendi"
  - "Universal House of Justice"
  - "Compilation"

- **Title dropdown**: ComboBox populated at startup from `manifest.csv`:
  - "All Titles" (default)
  - [All titles read from manifest.csv at launch, sorted alphabetically]
  - Adding a new compilation only requires updating manifest.csv — no code change

- **Search query**: Existing TextField for free-text content search

**Behavior**:
- If author/title selected: Use as SQL WHERE filters, skip AI parsing for author/title
- If both empty: Fall back to current AI parsing for full intent resolution
- Always use AI for final ranking of results

**Layout**: Filter bar placed **above** the input TextArea:
```
┌─────────────────────────────────────────────────────────────┐
│  Author: [ All Authors          ▼ ]  Title: [ All Titles  ▼ ]│
├─────────────────────────────────────────────────────────────┤
│  Research Topic                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ Describe your topic in paragraph form...             │  │
│  └──────────────────────────────────────────────────────┘  │
│                                      [ Begin Research → ]   │
├─────────────────────────────────────────────────────────────┤
│  Summary and sourced quotes appear here.                    │
└─────────────────────────────────────────────────────────────┘
```
The Title ComboBox grows to fill remaining space (`HBox.setHgrow(ALWAYS)`)
to avoid clipping long titles.

**Files to modify**:
- `src/main/java/com/bahairesearch/BahaiResearch.java` — add ComboBoxes, filter bar
- `src/main/java/com/bahairesearch/corpus/CorpusIngestService.java` — add `readTitlesFromManifest(AppConfig)`
- `src/main/java/com/bahairesearch/research/ResearchService.java` — add selectedAuthor/selectedTitle params

## 2. Fix Critical Author Matching Bug (HIGHEST PRIORITY)

**Problem**: Lines 757, 771 in LocalCorpusSearchService.java truncate "Baha'u'llah" to "bah", causing SQL `LIKE "%bah%"` to match both "Baha'u'llah" AND "Bab".

**Solution**: Use exact author matching with a definitive mapping:

```java
private static final Map<String, String> EXACT_AUTHOR_MAP = Map.of(
    "bahaullah", "Baha'u'llah",
    "baha u llah", "Baha'u'llah",
    "bahá'u'lláh", "Baha'u'llah",
    "bab", "Bab",
    "the bab", "Bab",
    "abdu l baha", "'Abdu'l-Baha",
    "abdul baha", "'Abdu'l-Baha",
    "'abdu'l-baha", "'Abdu'l-Baha",
    "shoghi effendi", "Shoghi Effendi",
    "uhj", "Universal House of Justice",
    "universal house of justice", "Universal House of Justice",
    "house of justice", "Universal House of Justice"
);
```

Replace `inferRequiredAuthor()` and `inferEffectiveAuthor()` with a single `resolveAuthor()` method that:
- Tries manual query parsing first (checking topic for author aliases)
- Falls back to AI-inferred author
- Returns exact author name from map (e.g., "Baha'u'llah" not "bah")

Update SQL query (line ~340) from:
```java
WHERE lower(d.author) LIKE ?  // with "%bah%"
```
To:
```java
WHERE d.author = ?  // with "Baha'u'llah"
```

**Impact**: Eliminates false positives, ensures 100% author precision.

### 2. Separate Content Terms from Metadata (HIGH PRIORITY)

**Problem**: Author and title words from queries get included in FTS search terms, diluting relevance.

**Solution**:
- Use AI-extracted author/title as SQL WHERE filters, NOT in FTS query
- Create `buildAuthorTokenSet()` to list all author name tokens
- Update `inferEffectiveConceptTerms()` to exclude author/title tokens
- Only pass pure concept terms to `toFtsQuery()`

**Example**:
- Query: "Baha'u'llah on unity and justice"
- Current: FTS query = "bahaullah* OR unity* OR justice*"
- Improved: Author filter = "Baha'u'llah", FTS query = "unity* AND justice*"

**Impact**: Results focus on content relevance, not accidental author name matches.

### 3. Improve FTS Query Logic (MEDIUM PRIORITY)

**Problem**: Line 813 joins all tokens with OR, returning passages with ANY term instead of most relevant.

**Solution**: Implement hybrid AND/OR logic in `toFtsQuery()`:

```java
private static String toFtsQuery(String topic, List<String> conceptTerms) {
    // Extract tokens from concepts only (author/title already removed)
    List<String> ftsTokens = extractTokensFromConcepts(conceptTerms);

    if (ftsTokens.size() <= 3) {
        // For 1-3 terms, require ALL (high precision)
        return String.join(" AND ", ftsTokens);
    } else {
        // For 4+ terms, require top 3 + any others (balance precision/recall)
        List<String> required = ftsTokens.subList(0, 3);
        List<String> optional = ftsTokens.subList(3, ftsTokens.size());
        return String.join(" AND ", required) + " AND (" + String.join(" OR ", optional) + ")";
    }
}
```

**Examples**:
- "unity justice" → "unity* AND justice*" (requires both)
- "unity justice peace" → "unity* AND justice* AND peace*" (requires all three)
- "unity justice peace harmony love" → "unity* AND justice* AND peace* AND (harmony* OR love*)" (requires 3+ terms)

**Impact**: Higher precision, fewer irrelevant results, better relevance ranking.

### 4. Code Cleanup (LOW PRIORITY)

**Remove unnecessary complexity**:
- Simplify or remove `startsWithAuthorAlias()` (lines 691-701)
- Consolidate duplicate author parsing logic
- Simplify `sourcePriority()` (lines 234-256) - UHJ special handling unnecessary with exact matching

**Impact**: Improved maintainability, reduced technical debt.

## Implementation Order

### Phase 1: Core Search Logic Fixes (Backend Only)
**Priority**: CRITICAL - Fix bugs affecting search quality

1. Fix author matching bug (prevents Bab/Baha'u'llah confusion)
2. Separate content terms from metadata (improves relevance)
3. Improve FTS query logic (AND instead of OR, balances precision/recall)

**Files Modified**:
- `LocalCorpusSearchService.java` (lines 40-127, 295-393, 610-814)

**Testing**: Unit tests + integration tests with existing UI

**Benefit**: Immediate improvement to search quality without UI changes

### Phase 2: JavaFX UI Enhancement (Frontend + Backend Integration)
**Priority**: HIGH - Adds user control and eliminates parsing ambiguity

1. Add Author dropdown to JavaFX UI
2. Add Title dropdown to JavaFX UI
3. Query database for distinct authors and titles
4. Update search orchestration to check if fields are populated
5. If populated: use as direct SQL filters, skip AI parsing
6. If empty: fall back to improved AI parsing from Phase 1

**Files Modified**:
- `BahaiResearch.java` (JavaFX UI setup, lines 50-150)
- `ResearchService.java` (add parameters for selectedAuthor, selectedTitle)
- `LocalCorpusSearchService.search()` (check if author/title provided, skip parsing if so)
- `CorpusContentRepository.java` (add methods: `getDistinctAuthors()`, `getDistinctTitles()`)

**Testing**: Manual UI testing + regression tests to ensure fallback works

**Benefit**: Power users get precision, casual users get flexibility

### Phase 3: Code Cleanup + Title Token FTS Filtering (Optional)
**Priority**: LOW - Reduces maintenance burden

1. Filter title words from FTS query terms (same approach as author tokens, but for all 76 titles)
   - Requires the title list already loaded by Phase 2 (manifest.csv at startup)
   - Deferred because 76 titles × multiple tokens = large exclusion set; impact needs measurement
2. Remove unnecessary author variation handling
3. Simplify code now that exact author matching is used
4. Add comprehensive documentation

Each phase is independently testable and can be rolled back if needed.

## Testing Strategy

### Phase 1 Tests (Backend Logic)

**Unit Tests**:
- Test `resolveAuthor()`: "bahaullah" → "Baha'u'llah", "bab" → "Bab" (NOT confused!)
- Test `toFtsQuery()`: Verify AND logic for 1-3 terms, hybrid for 4+ terms
- Test author token filtering: Verify author names excluded from FTS query

**Integration Tests**:
- Query: "Baha'u'llah unity" → Author filter "Baha'u'llah", FTS "unity*"
- Query: "quotes by the Bab on faith" → Author filter "Bab", FTS "faith*"
- Query: "unity justice peace" → No author filter, FTS "unity* AND justice* AND peace*"

**Regression Tests**:
- Create benchmark queries with expected results
- Verify no Bab/Baha'u'llah cross-contamination

### Phase 2 Tests (UI + Integration)

**UI Tests**:
- Verify dropdowns populate correctly with 6 authors + 76 titles
- Verify "All Authors" / "All Titles" are default selections
- Verify selections persist during search

**Structured Search Tests**:
- Select "Baha'u'llah" + query "unity" → Results only from Baha'u'llah, no AI parsing
- Select "Compilation" + query "tests" → Results only from Compilation documents
- Select specific title + query "justice" → Results only from that title

**Free-form Fallback Tests**:
- Leave dropdowns empty + paste paragraph → AI parses intent → Returns relevant quotes
- Leave dropdowns empty + "Bab on faith" → AI infers author → Returns Bab quotes

**Hybrid Tests**:
- Select "Baha'u'llah" + paste complex paragraph → Use author filter + AI concept extraction
- Select title + free-form query → Filter by title + AI concept extraction

**Edge Cases**:
- Empty query with author selected → Return top passages by that author
- Very long paragraph → AI extracts key concepts, search with AND logic

## Summary of Changes

### Phase 1: Backend Improvements (Immediate Impact)
- **Fix Bab/Baha'u'llah bug**: Use exact author matching, eliminate truncation
- **Separate metadata from content**: Author/title words excluded from FTS query
- **Improve FTS logic**: AND instead of OR for better relevance
- **Result**: Higher precision, fewer false positives, no author confusion

### Phase 2: UI Enhancement (User Control + Flexibility)
- **Add Author dropdown**: 6 distinct authors + "All Authors"
- **Add Title dropdown**: 76 titles + "All Titles"
- **Hybrid behavior**:
  - Fields populated → Direct SQL filters, skip AI parsing
  - Fields empty → AI parses intent (supports paragraph queries)
- **Result**: Power users get precision, casual users get flexibility

### Complete User Experience

```
┌─────────────────────────────────────────────────────────┐
│ Author: [Baha'u'llah ▼]    Title: [All Titles ▼]       │
├─────────────────────────────────────────────────────────┤
│ Search: [unity and justice in society]                  │
│                                           [Search 🔍]    │
└─────────────────────────────────────────────────────────┘

CASE A: Structured search
  • Author selected: Baha'u'llah
  • Query: "unity and justice in society"
  • Flow: WHERE author = 'Baha'u'llah' AND FTS "unity* AND justice* AND society*"
  • Result: Precise passages by Baha'u'llah on all three concepts

CASE B: Free-form paragraph
  • Author: All Authors (empty)
  • Query: [Paste essay paragraph about social transformation]
  • Flow: AI extracts concepts → Search with AND logic → AI ranks
  • Result: Relevant quotes addressing paragraph's themes

CASE C: Compilation search
  • Author: Compilation
  • Query: "spiritual education"
  • Flow: WHERE author = 'Compilation' AND FTS "spiritual* AND education*"
  • Result: Only compilation documents (mixed authorship)
```

## Verification

After Phase 1 (Backend):
1. Run debug mode (`debugIntent=true`) to inspect FTS queries and filters
2. Test specific author queries to verify exact matching
3. Verify no Bab/Baha'u'llah cross-contamination
4. Compare old vs new result relevance on benchmark queries
5. Monitor query performance (should remain <500ms)

After Phase 2 (UI):
1. Verify dropdowns populate with correct authors and titles
2. Test structured search: Select author → Search → Verify results match
3. Test free-form fallback: Leave empty → Paste paragraph → Verify AI parsing
4. Test hybrid: Select author → Paste complex query → Verify behavior
5. Test compilations: Select "Compilation" → Verify mixed-author results

## Critical Files

### Phase 1 (Backend Logic)
- **LocalCorpusSearchService.java** - Lines 40-127 (search method), 295-393 (findHits), 610-780 (author/concept inference), 795-814 (toFtsQuery)
- **AppConfig.java** - Add configuration options for FTS AND/OR thresholds
- **manifest.csv** - Reference for exact author names in EXACT_AUTHOR_MAP

### Phase 2 (UI + Integration)
- **BahaiResearch.java** - Lines 50-150 (JavaFX UI setup)
  - Add ComboBox for author selection
  - Add ComboBox for title selection
  - Update search button handler to pass selected values

- **ResearchService.java** - Update search method signature:
  ```java
  public ResearchReport search(
      String query,
      String selectedAuthor,  // NEW: null or "All Authors" means search all
      String selectedTitle,   // NEW: null or "All Titles" means search all
      AppConfig appConfig
  )
  ```

- **LocalCorpusSearchService.java** - Update search orchestration:
  ```java
  public static ResearchReport search(
      String topic,
      String explicitAuthor,  // NEW: from dropdown
      String explicitTitle,   // NEW: from dropdown
      AppConfig appConfig
  ) {
      // If explicit author provided, use it directly (skip AI parsing for author)
      // If explicit title provided, use it directly (skip AI parsing for title)
      // Only use AI for concepts if needed
  }
  ```

- **CorpusIngestService.java** - Add title reader method:
  ```java
  // Reads titles from manifest.csv at startup — no DB required
  public static List<String> readTitlesFromManifest(AppConfig appConfig);
  ```

## Why Hybrid Approach is Superior

### Benefits Over Pure AI Parsing

1. **Eliminates ambiguity**: User explicitly selects author, no AI confusion
2. **Faster**: Skip AI API call for intent parsing when using dropdowns
3. **Cheaper**: Fewer API calls = lower cost
4. **User control**: Power users can specify exactly what they want
5. **Still flexible**: Casual users can paste paragraphs and let AI figure it out

### Benefits Over Pure Structured Search

1. **Natural language support**: Users can ask questions conversationally
2. **Handles complex queries**: Paragraphs about topics work seamlessly
3. **No learning curve**: New users can start typing immediately
4. **Semantic understanding**: AI extracts concepts from messy input

### Why AND Logic Helps Both Modes

**Structured mode**: "unity justice peace" with Baha'u'llah selected
- Old OR: Returns any passage by Baha'u'llah mentioning any of these words
- New AND: Returns only passages discussing all three concepts together
- **Result**: Much higher relevance

**Free-form mode**: Paste paragraph about "spiritual transformation of society"
- AI extracts: ["spiritual", "transformation", "society", "collective", "change"]
- Old OR: Returns passages mentioning any one word
- New AND: Requires passages discussing multiple concepts together
- **Result**: Semantic relevance preserved

## Risks and Trade-offs

**Risk**: AND logic might be too restrictive (lower recall)
- **Mitigation**: Hybrid approach (require top 3, optional for rest), add fallback to OR if no results
- **Evidence**: Testing will show if recall drops significantly

**Risk**: UI complexity increases
- **Mitigation**: Dropdowns are optional filters, not required fields
- **Mitigation**: Default to "All Authors"/"All Titles" for familiar experience

**Risk**: Breaking existing queries users are accustomed to
- **Mitigation**: A/B testing mode with config flag, gradual rollout
- **Mitigation**: Phase 1 can be tested before Phase 2 UI changes

**Trade-off**: Precision vs Recall
- **Decision**: Favor precision - better to return fewer highly relevant passages than many marginally relevant ones
- **Aligns with**: "Local Corpus First" philosophy - deterministic, high-quality results

## JavaFX UI Implementation Details (Phase 2)

### Layout Changes

Update the search interface layout (in `BahaiResearch.java`):

```java
// Existing: Single search TextField
// Add above it:

// Author ComboBox — hardcoded (Baha'i authors are fixed)
Label authorLabel = new Label("Author:");
ComboBox<String> authorCombo = new ComboBox<>();
authorCombo.getItems().addAll(
    "All Authors",
    "'Abdu'l-Baha",
    "Bab",
    "Baha'u'llah",
    "Compilation",
    "Shoghi Effendi",
    "Universal House of Justice"
);
authorCombo.setValue("All Authors");

// Title ComboBox — populated from manifest.csv at startup
// Adding new compilations only requires updating manifest.csv
Label titleLabel = new Label("Title:");
ComboBox<String> titleCombo = new ComboBox<>();
titleCombo.getItems().add("All Titles");
try {
    AppConfig startupConfig = ConfigLoader.load();
    List<String> titles = CorpusIngestService.readTitlesFromManifest(startupConfig);
    titleCombo.getItems().addAll(titles);
} catch (Exception ignored) {
    // Graceful: dropdown shows only "All Titles" if config not yet available
}
titleCombo.setValue("All Titles");
HBox.setHgrow(titleCombo, Priority.ALWAYS);  // fills remaining width

HBox filterBox = new HBox(8, authorLabel, authorCombo, titleLabel, titleCombo);
filterBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
```

### Search Handler Update

```java
beginResearchButton.setOnAction(event -> {
    String topic = topicInputArea.getText();
    // Convert "All Authors" / "All Titles" sentinel to null for backend
    String authorFilter = "All Authors".equals(authorCombo.getValue()) ? null : authorCombo.getValue();
    String titleFilter  = "All Titles".equals(titleCombo.getValue())   ? null : titleCombo.getValue();
    runResearch(topicInputArea, outputArea, beginResearchButton, authorFilter, titleFilter);
});
```

## Configuration Options

Add to AppConfig.java:
- `ftsUseAndLogic` (boolean) - Enable AND logic (default: true)
- `ftsAndRequiredTerms` (int) - Number of required AND terms (default: 3)
- `debugSearchStrategy` (boolean) - Log FTS query details (default: false)
- `enableStructuredSearch` (boolean) - Show author/title dropdowns (default: true, Phase 2)
