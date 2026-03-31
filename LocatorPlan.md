# Plan: Meaningful Locators + Clickable URL

## Context
Current locator stored in DB is `p.1`, `p.2`... (sequential counter) — meaningless to users. We want each passage to carry a human-readable locator (e.g. `§ XLVII`, `[6]`, `¶ 47`) derived from content already embedded in the passage text by the existing profile builders. We also want the source URL to be a clickable hyperlink rather than plain text.

Phase 1 covers four schemes: `NONE`, `ROMAN`, `COMPILATION`, `PARA_SEQ`. Complex hierarchical schemes (Days of Remembrance, Kitab-i-Aqdas, etc.) are deferred to Phase 2 — they get `NONE` for now and can be upgraded later by updating manifest.csv and adding extraction logic.

## Files to Modify
- `data/corpus/curated/en/manifest.csv`
- `src/main/java/com/bahairesearch/corpus/CorpusIngestService.java`
- `src/main/java/com/bahairesearch/corpus/CorpusContentRepository.java`
- `src/main/java/com/bahairesearch/BahaiResearch.java`

## Files to Create
- `src/main/java/com/bahairesearch/corpus/LocatorExtractor.java`

---

## Step 1 — Update manifest.csv

Add `locator_scheme` as 6th column header:
```
filename,title,author,source_format,original_url,locator_scheme
```

Assign per row:

| Scheme | Files |
|---|---|
| `ROMAN` | `gleanings-writings-Bahaullah.docx`, `prayers-meditations.docx`, `19851001_001.docx` |
| `PARA_SEQ` | `gems-divine-mysteries.docx`, `kitab-i-iqan.docx` |
| `COMPILATION` | All Compilation-author rows + `the-institution-of-the-counsellors.docx` (NOT `bahai-prayers.docx` or `bahai-prayers-tablets-children.docx` — those use prayerBookProfile) |
| `NONE` (blank) | Everything else |

---

## Step 2 — Extend ManifestEntry + readManifestEntries()
**File:** `CorpusIngestService.java`

Add `locatorScheme` as 6th field to `ManifestEntry` record (line 526):
```java
private record ManifestEntry(
    String fileName, String title, String author,
    String sourceFormat, String originalUrl,
    String locatorScheme   // NEW
) {}
```

In `readManifestEntries()` (line 349), extend the `entries.add(...)` call:
```java
String locatorScheme = columns.size() >= 6
    ? columns.get(5).trim().toUpperCase(Locale.ROOT)
    : "NONE";
if (locatorScheme.isBlank()) locatorScheme = "NONE";
// then add to ManifestEntry constructor
```
The parser already ignores extra columns — adding col 6 is backward-compatible.

---

## Step 3 — Add 19851001_001 to isSectionedWork()
**File:** `CorpusIngestService.java` (line 456)

The Promise of World Peace uses Roman numeral section headers. Add it so `buildSectionedPassages()` prepends the section label to every passage (required for ROMAN locator extraction to work):
```java
return normalized.contains("gleanings")
    || normalized.contains("prayers-meditations")
    || normalized.contains("prayers and meditations")
    || normalized.contains("19851001_001");  // ADD THIS
```

---

## Step 4 — Create LocatorExtractor.java
**New file:** `src/main/java/com/bahairesearch/corpus/LocatorExtractor.java`

Package-private class. Extracts locator string from passage text without modifying the text.

```java
package com.bahairesearch.corpus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocatorExtractor {

    // ROMAN: passage starts with "– XLVII – " or "- IX - " (prepended by buildSectionedPassages)
    private static final Pattern ROMAN_PREFIX = Pattern.compile(
        "^[-\u2013\u2014]{1,2}\\s*([IVXLCDM]{1,10})\\s*[-\u2013\u2014]{0,2}\\s+",
        Pattern.CASE_INSENSITIVE);

    // COMPILATION: passage ends with "(...) [6]" citation appended by buildCompilationPassages
    private static final Pattern COMPILATION_SUFFIX = Pattern.compile(
        "\\([^)]{5,500}\\)\\s*\\[\\s*(\\d{1,4})\\s*\\]\\s*$");

    // PARA_SEQ: passage starts with "47. " or "47) " — inline typed paragraph number
    private static final Pattern PARA_SEQ_PREFIX = Pattern.compile(
        "^(\\d{1,4})[.)\\s]");

    private LocatorExtractor() {}

    static String extract(String passageText, String scheme) {
        if (passageText == null || passageText.isBlank()) return "";
        return switch (scheme == null ? "NONE" : scheme) {
            case "ROMAN"       -> extractRoman(passageText);
            case "COMPILATION" -> extractCompilation(passageText);
            case "PARA_SEQ"    -> extractParaSeq(passageText);
            default            -> "";
        };
    }

    private static String extractRoman(String text) {
        Matcher m = ROMAN_PREFIX.matcher(text);
        return m.find() ? "§ " + m.group(1).toUpperCase() : "";
    }

    private static String extractCompilation(String text) {
        Matcher m = COMPILATION_SUFFIX.matcher(text);
        return m.find() ? "[" + m.group(1) + "]" : "";
    }

    private static String extractParaSeq(String text) {
        Matcher m = PARA_SEQ_PREFIX.matcher(text);
        return m.find() ? "¶ " + m.group(1) : "";
    }
}
```

---

## Step 5 — Update replacePassages() signature
**File:** `CorpusContentRepository.java` (line 81)

Change signature to accept `locatorScheme`:
```java
public static void replacePassages(
    Connection connection, long docId,
    List<String> passages, String locatorScheme
) throws SQLException
```

Replace the `"p." + locator` assignment (line 92) with:
```java
int seq = 1;
for (String passage : passages) {
    String locator = LocatorExtractor.extract(passage, locatorScheme);
    if (locator.isBlank()) locator = "p." + seq;   // fallback keeps stable ID
    insertStatement.setLong(1, docId);
    insertStatement.setString(2, locator);
    insertStatement.setString(3, passage);
    insertStatement.addBatch();
    seq++;
}
```

---

## Step 6 — Update both call sites
**File:** `CorpusIngestService.java`

- **Line 284** (curated ingest loop): `replacePassages(connection, docId, passages, entry.locatorScheme())`
- **Line 183** (crawl ingest loop): `replacePassages(connection, docId, passages, "NONE")`

---

## Step 7 — Update BahaiResearch.java UI

### 7a — Replace TextArea output with ScrollPane + VBox

Remove `TextArea outputArea` (line 89). Replace with:
```java
TextArea summaryArea = new TextArea();
summaryArea.setEditable(false);
summaryArea.setWrapText(true);
summaryArea.setPromptText("Summary will appear here.");
summaryArea.setPrefRowCount(5);

VBox resultsBox = new VBox(8);
resultsBox.setPadding(new Insets(4, 0, 4, 0));

ScrollPane outputScrollPane = new ScrollPane(resultsBox);
outputScrollPane.setFitToWidth(true);
outputScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
```

Update VBox layout (line 102):
```java
VBox root = new VBox(filterBar, inputLabel, topicInputArea, controls, summaryArea, outputScrollPane);
VBox.setVgrow(outputScrollPane, Priority.ALWAYS);
```

### 7b — Update runResearch() signature (line 121)
Replace `TextArea outputArea` param with `TextArea summaryArea, VBox resultsBox`.
Update the button wiring at line 98 accordingly.

### 7c — Replace formatReport() with per-card rendering
In `setOnSucceeded` (line 139), replace `outputArea.setText(formatReport(report))` with:
```java
ResearchReport report = researchTask.getValue();
summaryArea.setText(report.summary() == null ? "" : report.summary());
resultsBox.getChildren().clear();
int[] num = {1};
for (QuoteResult quote : report.quotes()) {
    resultsBox.getChildren().add(buildQuoteCard(num[0]++, quote));
}
beginResearchButton.setDisable(false);
```

Update `setOnFailed` to use `summaryArea` instead of `outputArea`.

Delete `formatReport()` method entirely.

### 7d — Add buildQuoteCard() method
```java
private VBox buildQuoteCard(int number, QuoteResult quote) {
    Label quoteLabel = new Label(number + ") \"" + quote.quote() + "\"");
    quoteLabel.setWrapText(true);
    quoteLabel.setStyle("-fx-font-style: italic;");

    Label authorLabel = new Label("   Author: " + quote.author());
    Label bookLabel   = new Label("   Book: "   + quote.bookTitle());
    Label locLabel    = new Label("   Location: " + quote.paragraphOrPage());

    Hyperlink urlLink = new Hyperlink("   " + quote.sourceUrl());
    urlLink.setWrapText(true);
    urlLink.setOnAction(e -> {
        String url = quote.sourceUrl();
        if (url != null && !url.isBlank() && !url.equals("N/A")) {
            getHostServices().showDocument(url);
        }
    });

    VBox card = new VBox(2, quoteLabel, authorLabel, bookLabel, locLabel, urlLink);
    card.setPadding(new Insets(6, 0, 6, 0));
    card.setStyle("-fx-border-color: #cccccc; -fx-border-width: 0 0 1 0;");
    return card;
}
```

### 7e — New imports needed in BahaiResearch.java
```java
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ScrollPane;
import javafx.geometry.Insets;  // if not already imported
```

Note: `getHostServices()` is a method on `Application` — available directly since `BahaiResearch extends Application`. No extra field needed.

---

## Step 8 — Re-ingest

1. Delete the SQLite DB file (path from `AppConfig` / `CorpusPaths`)
2. `mvn compile -q` from `D:/AI-Python/BahaiResearch`
3. Start the app — fresh ingest runs automatically
4. Verify:
   - Gleanings result → Location: `§ XLVII` (or similar)
   - Peace compilation result → Location: `[6]` (or similar)
   - Kitab-i-Iqan result → Location: `¶ 47` (or similar)
   - Other books → Location: `p.N` fallback
   - URL field is a clickable hyperlink that opens the source document

---

## Risks / Notes
- **19851001_001 Roman markers**: if the docx uses a different dash/numeral style than Gleanings, `extractRoman()` may not match. If the fallback `p.N` appears after ingest, adjust the regex.
- **COMPILATION regex**: checks for `(...) [N]` at end of passage. If any passage has trailing whitespace or punctuation after `]`, add `[\s\p{Punct}]*$` to the pattern.
- **PARA_SEQ**: relies on inline typed paragraph numbers. If gems-divine-mysteries or kitab-i-iqan do NOT have inline `N.` prefixes, the fallback `p.N` will appear — harmless, but change scheme to `NONE` in manifest.csv if that's the case.
- **HTML Compilation files** (Family Life.xhtml, The Universal House of Justice.xhtml): these use `extractHtmlFilePassages()` not `buildCompilationPassages()`, so the `[N]` citation pattern may not be present. If locator extraction returns empty, fallback `p.N` applies — acceptable.
