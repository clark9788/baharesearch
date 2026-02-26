package com.bahairesearch.corpus;

import com.bahairesearch.config.AppConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Ingests bahai.org library pages into local corpus storage.
 */
public final class CorpusIngestService {

    private static final Pattern COMPILATION_ITEM_HEADER_PATTERN =
        Pattern.compile("\\([^\\)]{5,500}\\)\\s*\\[\\s*\\d{1,4}\\s*\\]");
    private static final Pattern COMPILATION_ITEM_MARKER_PATTERN =
        Pattern.compile("\\[\\s*\\d{1,4}\\s*\\]");

    // Matches Roman numeral section markers used in Gleanings (– XLVII –) and
    // Prayers and Meditations (-IX-): dashes surrounding uppercase Roman numerals.
    private static final Pattern ROMAN_NUMERAL_MARKER_PATTERN =
        Pattern.compile("(?i)^[-\u2013\u2014]{1,2} *[IVXLCDM]{1,10} *[-\u2013\u2014]{0,2}$");

    private CorpusIngestService() {
    }

    /**
     * Read all work titles from manifest.csv, sorted alphabetically.
     * Used to populate the Title dropdown in the UI at startup.
     * Adding a new compilation only requires updating manifest.csv — no code change needed.
     */
    public static List<String> readTitlesFromManifest(AppConfig appConfig) {
        CorpusPaths corpusPaths = CorpusPaths.fromConfig(appConfig);
        Path configured = Path.of(appConfig.corpusCuratedBaseDir());
        Path curatedBasePath = configured.isAbsolute()
            ? configured.normalize()
            : corpusPaths.basePath().resolve(configured).normalize();
        Path manifestPath = curatedBasePath.resolve(appConfig.corpusCuratedManifestFileName()).normalize();

        if (!Files.exists(manifestPath)) {
            return List.of();
        }
        try {
            List<ManifestEntry> entries = readManifestEntries(manifestPath);
            return entries.stream()
                .map(ManifestEntry::title)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .sorted()
                .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * Run ingest once when corpus is empty and auto-ingest is enabled.
     */
    public static void ingestIfConfigured(AppConfig appConfig) {
        CorpusPaths corpusPaths = CorpusPaths.fromConfig(appConfig);
        boolean curatedMode = appConfig.corpusCuratedIngestEnabled();
        try (Connection connection = CorpusConnectionFactory.open(corpusPaths)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }

            if (!curatedMode) {
                try {
                    CorpusContentRepository.backfillKnownAuthors(connection);
                } catch (SQLException exception) {
                    throw new IllegalStateException("Failed to backfill author metadata.", exception);
                }
            }

            int existingPassages = CorpusContentRepository.countPassages(connection);
            if (appConfig.corpusForceReingest()) {
                CorpusContentRepository.clearCorpusContent(connection);
                existingPassages = 0;
            }

            boolean shouldIngest = appConfig.corpusForceReingest()
                || (appConfig.corpusAutoIngestIfEmpty() && existingPassages == 0);
            if (!shouldIngest) {
                return;
            }

            if (curatedMode) {
                runCuratedIngest(connection, corpusPaths, appConfig);
            } else {
                runIngest(connection, corpusPaths, appConfig);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to inspect corpus state for ingest.", exception);
        }
    }

    private static void runIngest(Connection connection, CorpusPaths corpusPaths, AppConfig appConfig) {
        String startedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String runId = "ingest-" + Instant.now().toEpochMilli();

        OkHttpClient client = new OkHttpClient();
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(appConfig.corpusIngestSeedUrl());

        int fetchedPages = 0;
        int storedDocuments = 0;
        int storedPassages = 0;

        while (!queue.isEmpty() && visited.size() < appConfig.corpusIngestMaxPages()) {
            String nextUrl = canonicalize(queue.poll());
            if (nextUrl == null || !visited.add(nextUrl)) {
                continue;
            }
            if (!isAllowed(nextUrl, appConfig)) {
                continue;
            }

            try {
                Request request = new Request.Builder().url(nextUrl).get().build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        continue;
                    }

                    String body = response.body().string();
                    Document document = Jsoup.parse(body, nextUrl);
                    fetchedPages++;

                    List<String> passages = extractPreferredPassages(
                        client,
                        document,
                        nextUrl,
                        appConfig.corpusMinPassageLength()
                    );
                    if (!passages.isEmpty()) {
                        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
                        Path snapshotPath = writeSnapshot(corpusPaths, runId, nextUrl, body);
                        String author = extractAuthor(document, nextUrl);
                        String title = clean(document.title());
                        String hash = sha256Hex(body);

                        long docId = CorpusContentRepository.upsertDocument(
                            connection,
                            nextUrl,
                            author,
                            title,
                            runId,
                            timestamp,
                            hash,
                            snapshotPath.toString()
                        );
                        CorpusContentRepository.replacePassages(connection, docId, passages);
                        storedDocuments++;
                        storedPassages += passages.size();
                    }

                    for (Element link : document.select("a[href]")) {
                        String abs = canonicalize(link.attr("abs:href"));
                        if (abs != null && !visited.contains(abs) && isAllowed(abs, appConfig)) {
                            queue.add(abs);
                        }
                    }
                }

                if (appConfig.corpusIngestRequestDelayMillis() > 0) {
                    Thread.sleep(appConfig.corpusIngestRequestDelayMillis());
                }
            } catch (IOException ioException) {
                // Skip single-page failures and continue crawl.
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            } catch (SQLException persistenceException) {
                throw new IllegalStateException("Failed while persisting ingested corpus content.", persistenceException);
            }
        }

        String completedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String notes = "fetchedPages=" + fetchedPages
            + ", storedDocuments=" + storedDocuments
            + ", storedPassages=" + storedPassages;
        try {
            CorpusContentRepository.backfillKnownAuthors(connection);
            IngestRunRepository.insertRun(
                connection,
                runId,
                "crawl",
                "success",
                startedAt,
                completedAt,
                notes
            );
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to record ingest run metadata.", exception);
        }
    }

    private static void runCuratedIngest(Connection connection, CorpusPaths corpusPaths, AppConfig appConfig) {
        String startedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String runId = "curated-" + Instant.now().toEpochMilli();

        Path curatedBasePath = resolveCuratedBasePath(corpusPaths, appConfig);
        Path manifestPath = curatedBasePath.resolve(appConfig.corpusCuratedManifestFileName()).normalize();
        if (!Files.exists(manifestPath)) {
            throw new IllegalStateException("Curated manifest file not found: " + manifestPath);
        }

        int storedDocuments = 0;
        int storedPassages = 0;
        int skippedRows = 0;

        try {
            List<ManifestEntry> entries = readManifestEntries(manifestPath);
            for (ManifestEntry entry : entries) {
                Path sourceFile = resolveCuratedSourceFile(curatedBasePath, entry.fileName(), entry.sourceFormat());
                if (!Files.exists(sourceFile) || Files.isDirectory(sourceFile)) {
                    skippedRows++;
                    continue;
                }

                List<String> passages = extractPassagesFromCuratedFile(
                    sourceFile,
                    entry.sourceFormat(),
                    appConfig.corpusMinPassageLength(),
                    entry.author(),
                    entry.originalUrl(),
                    entry.title()
                );
                if (passages.isEmpty()) {
                    skippedRows++;
                    continue;
                }

                String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
                String canonicalUrl = entry.originalUrl().isBlank()
                    ? sourceFile.toUri().toString()
                    : canonicalize(entry.originalUrl());
                if (canonicalUrl == null || canonicalUrl.isBlank()) {
                    canonicalUrl = sourceFile.toUri().toString();
                }

                byte[] sourceBytes = Files.readAllBytes(sourceFile);
                long docId = CorpusContentRepository.upsertDocument(
                    connection,
                    canonicalUrl,
                    entry.author(),
                    entry.title(),
                    runId,
                    timestamp,
                    sha256Hex(sourceBytes),
                    sourceFile.toString()
                );
                CorpusContentRepository.replacePassages(connection, docId, passages);
                storedDocuments++;
                storedPassages += passages.size();
            }

            String completedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            String notes = "mode=curated"
                + ", manifest=" + manifestPath
                + ", storedDocuments=" + storedDocuments
                + ", storedPassages=" + storedPassages
                + ", skippedRows=" + skippedRows;
            IngestRunRepository.insertRun(
                connection,
                runId,
                "curated",
                "success",
                startedAt,
                completedAt,
                notes
            );
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Failed while ingesting curated local corpus files.", exception);
        }
    }

    private static Path resolveCuratedBasePath(CorpusPaths corpusPaths, AppConfig appConfig) {
        Path configured = Path.of(appConfig.corpusCuratedBaseDir());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        return corpusPaths.basePath().resolve(configured).normalize();
    }

    private static List<ManifestEntry> readManifestEntries(Path manifestPath) throws IOException {
        List<ManifestEntry> entries = new ArrayList<>();
        List<String> lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return entries;
        }

        int startIndex = 0;
        if (looksLikeHeader(lines.getFirst())) {
            startIndex = 1;
        }

        for (int i = startIndex; i < lines.size(); i++) {
            String rawLine = lines.get(i);
            if (rawLine == null || rawLine.trim().isEmpty()) {
                continue;
            }
            List<String> columns = parseCsvLine(rawLine);
            if (columns.size() < 5) {
                continue;
            }

            String fileName = columns.get(0).trim();
            String title = columns.get(1).trim();
            String author = columns.get(2).trim();
            String sourceFormat = columns.get(3).trim().toLowerCase(Locale.ROOT);
            String originalUrl = columns.get(4).trim();

            if (fileName.isBlank() || title.isBlank()) {
                continue;
            }

            entries.add(new ManifestEntry(
                fileName,
                title,
                author.isBlank() ? "Unknown" : author,
                normalizeSourceFormat(sourceFormat, fileName),
                originalUrl
            ));
        }
        return entries;
    }

    private static String normalizeSourceFormat(String sourceFormat, String fileName) {
        if (!sourceFormat.isBlank()) {
            if ("docx".equals(sourceFormat) || "html".equals(sourceFormat) || "pdf".equals(sourceFormat)) {
                return sourceFormat;
            }
        }

        String normalizedName = fileName.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".docx")) {
            return "docx";
        }
        if (normalizedName.endsWith(".pdf")) {
            return "pdf";
        }
        return "html";
    }

    private static Path resolveCuratedSourceFile(Path curatedBasePath, String fileName, String sourceFormat) {
        Path direct = curatedBasePath.resolve(fileName).normalize();
        if (Files.exists(direct)) {
            return direct;
        }

        Path byFormat = curatedBasePath.resolve(sourceFormat).resolve(fileName).normalize();
        if (Files.exists(byFormat)) {
            return byFormat;
        }

        return switch (sourceFormat) {
            case "docx" -> curatedBasePath.resolve("docx").resolve(fileName).normalize();
            case "pdf" -> curatedBasePath.resolve("pdf").resolve(fileName).normalize();
            default -> curatedBasePath.resolve("html").resolve(fileName).normalize();
        };
    }

    private static List<String> extractPassagesFromCuratedFile(
        Path sourceFile,
        String sourceFormat,
        int minLength,
        String author,
        String originalUrl,
        String title
    ) throws IOException {
        boolean hiddenWordsProfile = isHiddenWordsWork(sourceFile, originalUrl, title);
        boolean compilationProfile = isCompilationWork(author);
        boolean prayerBookProfile = isPrayerBookWork(sourceFile, originalUrl, title);
        boolean sectionedProfile = isSectionedWork(sourceFile, originalUrl, title);
        return switch (sourceFormat) {
            case "docx" -> extractDocxPassages(
                Files.readAllBytes(sourceFile),
                minLength,
                hiddenWordsProfile,
                compilationProfile,
                prayerBookProfile,
                sectionedProfile
            );
            case "pdf" -> extractPdfPassages(Files.readAllBytes(sourceFile), minLength);
            default -> extractHtmlFilePassages(sourceFile, minLength, originalUrl);
        };
    }

    private static boolean isCompilationWork(String author) {
        return "compilation".equalsIgnoreCase(clean(author));
    }

    private static boolean isHiddenWordsWork(Path sourceFile, String originalUrl, String title) {
        String combined = (sourceFile == null ? "" : sourceFile.getFileName().toString())
            + " "
            + (originalUrl == null ? "" : originalUrl)
            + " "
            + (title == null ? "" : title);
        String normalized = combined.toLowerCase(Locale.ROOT);
        return normalized.contains("hidden-words") || normalized.contains("hidden words");
    }

    // Bahá'í Prayers and Bahá'í Prayers and Tablets for Children:
    // individual prayers delimited by attribution lines (—Bahá'u'lláh, —'Abdu'l-Bahá, etc.)
    private static boolean isPrayerBookWork(Path sourceFile, String originalUrl, String title) {
        String combined = (sourceFile == null ? "" : sourceFile.getFileName().toString())
            + " "
            + (originalUrl == null ? "" : originalUrl)
            + " "
            + (title == null ? "" : title);
        String normalized = combined.toLowerCase(Locale.ROOT);
        return normalized.contains("bahai-prayers") || normalized.contains("bahai prayers");
    }

    // Gleanings (– XLVII –) and Prayers and Meditations (-IX-):
    // sections delimited by Roman numeral markers; label is prepended to each passage.
    private static boolean isSectionedWork(Path sourceFile, String originalUrl, String title) {
        String combined = (sourceFile == null ? "" : sourceFile.getFileName().toString())
            + " "
            + (originalUrl == null ? "" : originalUrl)
            + " "
            + (title == null ? "" : title);
        String normalized = combined.toLowerCase(Locale.ROOT);
        return normalized.contains("gleanings")
            || normalized.contains("prayers-meditations")
            || normalized.contains("prayers and meditations");
    }

    private static List<String> extractHtmlFilePassages(Path sourceFile, int minLength, String originalUrl) throws IOException {
        String html = Files.readString(sourceFile, StandardCharsets.UTF_8);
        String baseUrl = originalUrl == null || originalUrl.isBlank()
            ? sourceFile.toUri().toString()
            : originalUrl;
        Document document = Jsoup.parse(html, baseUrl);
        return extractPassages(document, minLength);
    }

    private static List<String> extractPdfPassages(byte[] pdfBytes, int minLength) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = clean(stripper.getText(document));
            if (text.isBlank()) {
                return List.of();
            }

            List<String> passages = new ArrayList<>();
            for (String row : text.split("\\R{2,}")) {
                String cleaned = clean(row);
                if (cleaned.length() >= minLength) {
                    passages.add(cleaned);
                }
            }

            if (passages.isEmpty() && text.length() >= minLength) {
                passages.add(text);
            }
            return passages;
        }
    }

    private static boolean looksLikeHeader(String line) {
        String normalized = line == null ? "" : line.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
        return normalized.contains("filename")
            && normalized.contains("title")
            && normalized.contains("author")
            && normalized.contains("source_format");
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private record ManifestEntry(
        String fileName,
        String title,
        String author,
        String sourceFormat,
        String originalUrl
    ) {
    }

    private static boolean isAllowed(String url, AppConfig appConfig) {
        String normalized = url.toLowerCase(Locale.ROOT);
        return normalized.startsWith(appConfig.corpusSourceBaseUrl().toLowerCase(Locale.ROOT))
            && normalized.contains("/library/")
            && !normalized.endsWith(".pdf")
            && !normalized.endsWith(".docx")
            && !normalized.endsWith(".epub");
    }

    private static List<String> extractPreferredPassages(
        OkHttpClient client,
        Document document,
        String pageUrl,
        int minLength
    ) {
        String docxUrl = findDocxUrl(document);
        if (!docxUrl.isBlank()) {
            try {
                byte[] docxBytes = downloadBinary(client, docxUrl);
                List<String> docxPassages = extractDocxPassages(docxBytes, minLength, false, false, false, false);
                if (!docxPassages.isEmpty()) {
                    return docxPassages;
                }
            } catch (IOException ignored) {
                // Fallback to HTML passage extraction below.
            }
        }

        return extractPassages(document, minLength);
    }

    private static String findDocxUrl(Document document) {
        for (Element link : document.select("a[href]")) {
            String candidate = canonicalize(link.attr("abs:href"));
            if (candidate != null && candidate.toLowerCase(Locale.ROOT).endsWith(".docx")) {
                return candidate;
            }
        }
        return "";
    }

    private static byte[] downloadBinary(OkHttpClient client, String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed to download DOCX: " + url);
            }
            return response.body().bytes();
        }
    }

    private static List<String> extractDocxPassages(
        byte[] docxBytes,
        int minLength,
        boolean hiddenWordsProfile,
        boolean compilationProfile,
        boolean prayerBookProfile,
        boolean sectionedProfile
    )
        throws IOException {
        String xml = readWordDocumentXml(docxBytes);
        if (xml.isBlank()) {
            return List.of();
        }

        String normalized = xml
            .replaceAll("(?s)<w:tab[^>]*/>", " ")
            .replaceAll("(?s)</w:p>", "\n")
            .replaceAll("(?s)<[^>]+>", " ");

        List<String> rows = new ArrayList<>();
        for (String row : normalized.split("\\R+")) {
            String text = cleanDocxExtractedLine(clean(Jsoup.parse(row).text()));
            if (!text.isBlank()) {
                rows.add(text);
            }
        }

        if (hiddenWordsProfile) {
            return buildHiddenWordsPassages(rows);
        }

        if (prayerBookProfile) {
            return buildPrayerBookPassages(rows);
        }

        if (compilationProfile) {
            return buildCompilationPassages(rows, minLength);
        }

        if (sectionedProfile) {
            return buildSectionedPassages(rows, minLength);
        }

        return mergeDocxRowsIntoPassages(rows, minLength);
    }

    private static List<String> buildCompilationPassages(List<String> rows, int minLength) {
        List<String> expandedRows = new ArrayList<>();
        for (String row : rows) {
            expandedRows.addAll(splitCompilationRow(row));
        }

        List<String> passages = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (String row : expandedRows) {
            if (isCompilationCitationLine(row)) {
                int markerEnd = findCompilationMarkerEnd(row);
                String citationPart = markerEnd > 0 ? clean(row.substring(0, markerEnd)) : clean(row);
                String trailingQuotePart = markerEnd > 0 ? clean(row.substring(markerEnd)) : "";

                if (!citationPart.isBlank()) {
                    if (!current.isEmpty()) {
                        current.add(citationPart);
                        passages.add(clean(String.join(" ", current)));
                        current.clear();
                    } else if (!passages.isEmpty()) {
                        int lastIndex = passages.size() - 1;
                        passages.set(lastIndex, clean(passages.get(lastIndex) + " " + citationPart));
                    }
                }

                if (!trailingQuotePart.isBlank()) {
                    current.add(trailingQuotePart);
                }
                continue;
            }

            current.add(row);
        }

        if (!current.isEmpty()) {
            passages.add(clean(String.join(" ", current)));
        }

        if (passages.isEmpty()) {
            return mergeDocxRowsIntoPassages(rows, minLength);
        }

        return passages;
    }

    private static boolean isCompilationCitationLine(String row) {
        String trimmed = clean(row);
        if (!trimmed.startsWith("(")) {
            return false;
        }
        return COMPILATION_ITEM_MARKER_PATTERN.matcher(trimmed).find();
    }

    private static int findCompilationMarkerEnd(String row) {
        Matcher matcher = COMPILATION_ITEM_MARKER_PATTERN.matcher(row);
        if (matcher.find()) {
            return matcher.end();
        }
        return -1;
    }

    private static boolean isCompilationItemStart(String row) {
        Matcher markerMatcher = COMPILATION_ITEM_MARKER_PATTERN.matcher(row);
        if (!markerMatcher.find()) {
            return false;
        }

        int markerStart = markerMatcher.start();
        int sourceStart = row.lastIndexOf('(', markerStart);
        return sourceStart >= 0 && markerStart - sourceStart <= 280;
    }

    private static List<String> splitCompilationRow(String row) {
        List<String> segments = new ArrayList<>();
        Matcher markerMatcher = COMPILATION_ITEM_MARKER_PATTERN.matcher(row);
        List<Integer> markerStarts = new ArrayList<>();
        while (markerMatcher.find()) {
            markerStarts.add(markerMatcher.start());
        }

        if (markerStarts.size() <= 1) {
            segments.add(row);
            return segments;
        }

        List<Integer> itemStarts = new ArrayList<>();
        for (int i = 0; i < markerStarts.size(); i++) {
            int markerStart = markerStarts.get(i);
            int floor = i == 0 ? 0 : markerStarts.get(i - 1);
            int inferredStart = row.lastIndexOf('(', markerStart);
            if (inferredStart < floor || markerStart - inferredStart > 280) {
                inferredStart = floor;
            }
            itemStarts.add(Math.max(inferredStart, floor));
        }

        for (int i = 0; i < itemStarts.size(); i++) {
            int start = itemStarts.get(i);
            int end = i + 1 < itemStarts.size() ? itemStarts.get(i + 1) : row.length();
            String part = clean(row.substring(start, end));
            if (!part.isBlank()) {
                segments.add(part);
            }
        }

        return segments.isEmpty() ? List.of(row) : segments;
    }

    private static List<String> buildHiddenWordsPassages(List<String> rows) {
        List<String> passages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        boolean started = false;

        for (String row : rows) {
            if (isHiddenWordsEntryStart(row)) {
                started = true;
                if (!current.isEmpty()) {
                    passages.add(clean(String.join(" ", current)));
                    current.clear();
                }
            }

            if (!started) {
                continue;
            }
            current.add(row);
        }

        if (!current.isEmpty()) {
            passages.add(clean(String.join(" ", current)));
        }

        if (passages.isEmpty()) {
            return mergeDocxRowsIntoPassages(rows, 1);
        }

        return passages;
    }

    private static boolean isHiddenWordsEntryStart(String row) {
        String value = clean(row);
        if (value.matches("^\\d{1,3}$")) {
            return true;
        }
        if (value.matches("^\\d{1,3}[).:-]?\\s+.+$")) {
            return true;
        }

        String upper = value.toUpperCase(Locale.ROOT);
        return value.length() <= 90
            && value.equals(upper)
            && value.startsWith("O ")
            && value.endsWith("!");
    }

    private static List<String> mergeDocxRowsIntoPassages(List<String> rows, int minLength) {
        List<String> passages = new ArrayList<>();
        List<String> pendingShortLines = new ArrayList<>();

        for (String row : rows) {
            if (row.length() >= minLength) {
                if (!pendingShortLines.isEmpty()) {
                    List<String> combinedLines = new ArrayList<>(pendingShortLines);
                    combinedLines.add(row);
                    passages.add(clean(String.join(" ", combinedLines)));
                    pendingShortLines.clear();
                } else {
                    passages.add(row);
                }
                continue;
            }

            pendingShortLines.add(row);
        }

        if (!pendingShortLines.isEmpty()) {
            String tail = clean(String.join(" ", pendingShortLines));
            if (tail.length() >= Math.max(50, minLength / 2)) {
                passages.add(tail);
            } else if (!passages.isEmpty()) {
                int lastIndex = passages.size() - 1;
                passages.set(lastIndex, clean(passages.get(lastIndex) + " " + tail));
            }
        }

        return passages;
    }

    /**
     * Splits a prayers book into individual prayers.
     * Each prayer ends with an attribution line (—Bahá'u'lláh, —'Abdu'l-Bahá, etc.).
     * Section headings ("Evening", "Infants") naturally become a prefix on the first
     * prayer in that section, which is harmless and provides light context.
     * Attribution line is kept at the end of the passage per display convention.
     */
    private static List<String> buildPrayerBookPassages(List<String> rows) {
        List<String> passages = new ArrayList<>();
        List<String> current = new ArrayList<>();

        for (String row : rows) {
            current.add(row);
            if (isAttributionLine(row)) {
                passages.add(clean(String.join(" ", current)));
                current.clear();
            }
        }

        // Trailing content after the last attribution (e.g., back matter) — keep if substantial
        if (!current.isEmpty()) {
            String tail = clean(String.join(" ", current));
            if (tail.length() >= 50) {
                passages.add(tail);
            }
        }

        if (passages.isEmpty()) {
            return mergeDocxRowsIntoPassages(rows, 1);
        }
        return passages;
    }

    /**
     * Splits a sectioned work (Gleanings, Prayers and Meditations) on Roman numeral markers.
     * Each section label (– XLVII –, -IX-) is prepended to every passage produced from
     * that section, giving a searchable and citable section reference in the passage text.
     */
    private static List<String> buildSectionedPassages(List<String> rows, int minLength) {
        List<String> passages = new ArrayList<>();
        String currentLabel = "";
        List<String> currentSection = new ArrayList<>();

        for (String row : rows) {
            if (isRomanNumeralMarker(row)) {
                if (!currentSection.isEmpty()) {
                    String label = currentLabel;
                    for (String p : mergeDocxRowsIntoPassages(currentSection, minLength)) {
                        passages.add(label.isBlank() ? p : label + " " + p);
                    }
                    currentSection.clear();
                }
                currentLabel = row;
            } else {
                currentSection.add(row);
            }
        }

        // Emit the final section
        if (!currentSection.isEmpty()) {
            String label = currentLabel;
            for (String p : mergeDocxRowsIntoPassages(currentSection, minLength)) {
                passages.add(label.isBlank() ? p : label + " " + p);
            }
        }

        if (passages.isEmpty()) {
            return mergeDocxRowsIntoPassages(rows, minLength);
        }
        return passages;
    }

    /**
     * An attribution line ends a prayer: em-dash followed by author name (short, ≤ 80 chars).
     * Examples: "—Bahá'u'lláh", "—'Abdu'l-Bahá", "—The Báb", "—Shoghi Effendi"
     */
    private static boolean isAttributionLine(String row) {
        return row.startsWith("\u2014") && row.length() > 1 && row.length() <= 80;
    }

    /** A Roman numeral section marker: dashes surrounding Roman numeral digits. */
    private static boolean isRomanNumeralMarker(String row) {
        return ROMAN_NUMERAL_MARKER_PATTERN.matcher(row).matches();
    }

    private static String cleanDocxExtractedLine(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }

        String cleaned = line;
        // Remove DOCX drawing/layout numeric artifact fragments while preserving the true paragraph number.
        // Example: "... -540385 0 63. 0 0 63. Text" -> "... 63. Text"
        cleaned = cleaned.replaceAll("-?\\d+\\s+0\\s+(\\d{1,3}\\.)\\s+0\\s+0\\s+\\1\\s*", "$1 ");
        // Defensive fallback for malformed variants where only a single synthetic prefix appears.
        // Example: "... -540385 0 63. Text" -> "... 63. Text"
        cleaned = cleaned.replaceAll("-?\\d+\\s+0\\s+(\\d{1,3}\\.)\\s*", "$1 ");

        return clean(cleaned);
    }

    private static String readWordDocumentXml(byte[] docxBytes) throws IOException {
        try (InputStream inputStream = new java.io.ByteArrayInputStream(docxBytes);
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zipInputStream.read(buffer)) >= 0) {
                        outputStream.write(buffer, 0, read);
                    }
                    return outputStream.toString(StandardCharsets.UTF_8);
                }
            }
        }
        return "";
    }

    private static String extractAuthor(Document document, String canonicalUrl) {
        String inferredFromUrl = inferAuthorFromUrl(canonicalUrl);
        if (!inferredFromUrl.isBlank()) {
            return inferredFromUrl;
        }

        String metaAuthor = clean(document.select("meta[name=author]").attr("content"));
        if (!metaAuthor.isBlank()) {
            return metaAuthor;
        }

        String inferredFromTitle = inferAuthorFromTitle(document.title());
        if (!inferredFromTitle.isBlank()) {
            return inferredFromTitle;
        }

        String citationAuthor = clean(document.select("[itemprop=author]").text());
        if (!citationAuthor.isBlank()) {
            return citationAuthor;
        }

        String body = document.body() == null ? "" : document.body().text().toLowerCase(Locale.ROOT);
        if (body.contains("bahá’u’lláh") || body.contains("baha'u'llah")) {
            return "Bahá’u’lláh";
        }
        if (body.contains("‘abdu’l-bahá") || body.contains("abdu'l-baha")) {
            return "‘Abdu’l-Bahá";
        }
        if (body.contains("shoghi effendi")) {
            return "Shoghi Effendi";
        }
        if (body.contains("universal house of justice")) {
            return "Universal House of Justice";
        }
        return "Unknown";
    }

    private static String inferAuthorFromUrl(String canonicalUrl) {
        String normalized = canonicalUrl == null ? "" : canonicalUrl.toLowerCase(Locale.ROOT);
        if (normalized.contains("/authoritative-texts/shoghi-effendi/")) {
            return "Shoghi Effendi";
        }
        if (normalized.contains("/authoritative-texts/the-universal-house-of-justice/")) {
            return "Universal House of Justice";
        }
        if (normalized.contains("/authoritative-texts/the-bab/")) {
            return "The Báb";
        }
        if (normalized.contains("/authoritative-texts/abdul-baha/")) {
            return "‘Abdu’l-Bahá";
        }
        if (normalized.contains("/authoritative-texts/bahaullah/")) {
            return "Bahá’u’lláh";
        }
        return "";
    }

    private static String inferAuthorFromTitle(String title) {
        String normalized = clean(title).toLowerCase(Locale.ROOT);
        if (normalized.contains("writings of shoghi effendi")) {
            return "Shoghi Effendi";
        }
        if (normalized.contains("messages of the universal house of justice")) {
            return "Universal House of Justice";
        }
        if (normalized.contains("writings and talks of") && normalized.contains("abdu")) {
            return "‘Abdu’l-Bahá";
        }
        if (normalized.contains("writings of the báb") || normalized.contains("writings of the bab")) {
            return "The Báb";
        }
        if (normalized.contains("writings of bahá’u’lláh") || normalized.contains("writings of baha'u'llah")) {
            return "Bahá’u’lláh";
        }
        return "";
    }

    private static List<String> extractPassages(Document document, int minLength) {
        List<String> passages = new ArrayList<>();
        for (Element paragraph : document.select("p")) {
            String text = clean(paragraph.text());
            if (text.length() >= minLength) {
                passages.add(text);
            }
        }

        if (!passages.isEmpty()) {
            return passages;
        }

        String bodyText = clean(document.body() == null ? "" : document.body().text());
        if (bodyText.length() >= minLength) {
            passages.add(bodyText);
        }
        return passages;
    }

    private static Path writeSnapshot(CorpusPaths corpusPaths, String runId, String url, String html) throws IOException {
        Path runDir = corpusPaths.snapshotsPath().resolve(runId);
        Files.createDirectories(runDir);

        String fileName = sanitizeFileName(url) + ".html";
        Path target = runDir.resolve(fileName);
        Files.writeString(target, html, StandardCharsets.UTF_8);
        return target;
    }

    private static String sanitizeFileName(String value) {
        String sanitized = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.length() > 120 ? sanitized.substring(0, 120) : sanitized;
    }

    private static String canonicalize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        int hashIndex = trimmed.indexOf('#');
        if (hashIndex >= 0) {
            trimmed = trimmed.substring(0, hashIndex);
        }
        int queryIndex = trimmed.indexOf('?');
        if (queryIndex >= 0) {
            trimmed = trimmed.substring(0, queryIndex);
        }
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable.", exception);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value);
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable.", exception);
        }
    }
}
