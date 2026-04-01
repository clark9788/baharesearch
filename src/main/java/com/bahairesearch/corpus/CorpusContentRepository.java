package com.bahairesearch.corpus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Handles document and passage persistence for local corpus ingestion.
 */
public final class CorpusContentRepository {

    private CorpusContentRepository() {
    }

    /**
     * Return total number of stored passages.
     */
    public static int countPassages(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM passages");
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    /**
     * Insert or update one document row and return its doc_id.
     */
    public static long upsertDocument(
        Connection connection,
        String canonicalUrl,
        String author,
        String title,
        String ingestVersion,
        String ingestedAt,
        String contentHash,
        String sourceSnapshotPath
    ) throws SQLException {
        String upsertSql = """
            INSERT INTO documents (canonical_url, author, title, ingest_version, ingested_at, content_hash, source_snapshot_path)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(canonical_url) DO UPDATE SET
                author = excluded.author,
                title = excluded.title,
                ingest_version = excluded.ingest_version,
                ingested_at = excluded.ingested_at,
                content_hash = excluded.content_hash,
                source_snapshot_path = excluded.source_snapshot_path
            """;

        try (PreparedStatement statement = connection.prepareStatement(upsertSql)) {
            statement.setString(1, canonicalUrl);
            statement.setString(2, author);
            statement.setString(3, title);
            statement.setString(4, ingestVersion);
            statement.setString(5, ingestedAt);
            statement.setString(6, contentHash);
            statement.setString(7, sourceSnapshotPath);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT doc_id FROM documents WHERE canonical_url = ?"
        )) {
            statement.setString(1, canonicalUrl);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
            }
        }

        throw new SQLException("Unable to resolve doc_id for URL: " + canonicalUrl);
    }

    /**
     * Replace all stored passages for one document.
     * Uses the anchor ID from each ExtractedPassage as the locator; falls back to "p.N" for
     * docx/pdf passages that have no anchor ID.
     */
    public static void replacePassages(Connection connection, long docId, List<ExtractedPassage> passages) throws SQLException {
        try (PreparedStatement deleteStatement = connection.prepareStatement("DELETE FROM passages WHERE doc_id = ?")) {
            deleteStatement.setLong(1, docId);
            deleteStatement.executeUpdate();
        }

        String insertSql = "INSERT INTO passages (doc_id, locator, text_content) VALUES (?, ?, ?)";
        try (PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
            int seq = 1;
            for (ExtractedPassage passage : passages) {
                String locator = (passage.locator() != null && !passage.locator().isBlank())
                    ? passage.locator()
                    : "p." + seq;
                insertStatement.setLong(1, docId);
                insertStatement.setString(2, locator);
                insertStatement.setString(3, passage.text());
                insertStatement.addBatch();
                seq++;
            }
            insertStatement.executeBatch();
        }
    }

    /**
     * Backfill author metadata using deterministic URL and title patterns.
     */
    public static int backfillKnownAuthors(Connection connection) throws SQLException {
        String sql = """
            UPDATE documents
            SET author = CASE
                WHEN lower(canonical_url) LIKE '%/authoritative-texts/shoghi-effendi/%' THEN 'Shoghi Effendi'
                WHEN lower(canonical_url) LIKE '%/authoritative-texts/the-universal-house-of-justice/%' THEN 'Universal House of Justice'
                WHEN lower(canonical_url) LIKE '%/authoritative-texts/the-bab/%' THEN 'The Báb'
                WHEN lower(canonical_url) LIKE '%/authoritative-texts/abdul-baha/%' THEN '‘Abdu’l-Bahá'
                WHEN lower(canonical_url) LIKE '%/authoritative-texts/bahaullah/%' THEN 'Bahá’u’lláh'
                WHEN lower(title) LIKE '%writings of shoghi effendi%' THEN 'Shoghi Effendi'
                WHEN lower(title) LIKE '%messages of the universal house of justice%' THEN 'Universal House of Justice'
                WHEN lower(title) LIKE '%writings and talks of ‘abdu%'
                     OR lower(title) LIKE '%writings and talks of ''abdu%'
                     OR lower(title) LIKE '%abdul-baha%' THEN '‘Abdu’l-Bahá'
                WHEN lower(title) LIKE '%writings of the báb%'
                     OR lower(title) LIKE '%writings of the bab%' THEN 'The Báb'
                WHEN lower(title) LIKE '%writings of bahá’u’lláh%'
                     OR lower(title) LIKE '%writings of baha''u''llah%'
                     OR lower(title) LIKE '%writings of bahaullah%' THEN 'Bahá’u’lláh'
                ELSE author
            END
            WHERE
                lower(canonical_url) LIKE '%/authoritative-texts/shoghi-effendi/%'
                OR lower(canonical_url) LIKE '%/authoritative-texts/the-universal-house-of-justice/%'
                OR lower(canonical_url) LIKE '%/authoritative-texts/the-bab/%'
                OR lower(canonical_url) LIKE '%/authoritative-texts/abdul-baha/%'
                OR lower(canonical_url) LIKE '%/authoritative-texts/bahaullah/%'
                OR lower(title) LIKE '%writings of shoghi effendi%'
                OR lower(title) LIKE '%messages of the universal house of justice%'
                OR lower(title) LIKE '%writings and talks of ‘abdu%'
                OR lower(title) LIKE '%writings and talks of ''abdu%'
                OR lower(title) LIKE '%abdul-baha%'
            """;

        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    /**
     * Remove all ingested documents and passages, preserving schema and run history.
     */
    public static void clearCorpusContent(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM passages");
            statement.executeUpdate("DELETE FROM documents");
        }
    }
}
