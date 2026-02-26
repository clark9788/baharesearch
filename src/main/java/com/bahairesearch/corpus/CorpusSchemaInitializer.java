package com.bahairesearch.corpus;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates and upgrades SQLite schema objects required by the local corpus.
 */
public final class CorpusSchemaInitializer {

    private CorpusSchemaInitializer() {
    }

    /**
     * Ensure Phase 1 corpus tables and indexes exist.
     */
    public static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS ingest_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id TEXT NOT NULL UNIQUE,
                    run_type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    completed_at TEXT,
                    notes TEXT
                )
                """);

            statement.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    doc_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    canonical_url TEXT NOT NULL UNIQUE,
                    author TEXT,
                    title TEXT,
                    ingest_version TEXT NOT NULL,
                    ingested_at TEXT NOT NULL,
                    content_hash TEXT,
                    source_snapshot_path TEXT
                )
                """);

            statement.execute("""
                CREATE TABLE IF NOT EXISTS passages (
                    passage_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    doc_id INTEGER NOT NULL,
                    locator TEXT,
                    text_content TEXT NOT NULL,
                    FOREIGN KEY (doc_id) REFERENCES documents(doc_id) ON DELETE CASCADE
                )
                """);

            statement.execute("CREATE INDEX IF NOT EXISTS idx_documents_author ON documents(author)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_documents_title ON documents(title)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_passages_doc_id ON passages(doc_id)");

            statement.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS passages_fts USING fts5(
                    text_content,
                    content='passages',
                    content_rowid='passage_id'
                )
                """);

            statement.execute("""
                CREATE TRIGGER IF NOT EXISTS passages_ai AFTER INSERT ON passages BEGIN
                    INSERT INTO passages_fts(rowid, text_content) VALUES (new.passage_id, new.text_content);
                END
                """);

            statement.execute("""
                CREATE TRIGGER IF NOT EXISTS passages_ad AFTER DELETE ON passages BEGIN
                    INSERT INTO passages_fts(passages_fts, rowid, text_content)
                    VALUES('delete', old.passage_id, old.text_content);
                END
                """);

            statement.execute("""
                CREATE TRIGGER IF NOT EXISTS passages_au AFTER UPDATE ON passages BEGIN
                    INSERT INTO passages_fts(passages_fts, rowid, text_content)
                    VALUES('delete', old.passage_id, old.text_content);
                    INSERT INTO passages_fts(rowid, text_content)
                    VALUES(new.passage_id, new.text_content);
                END
                """);
        }
    }
}
