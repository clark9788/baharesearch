package com.bahairesearch.corpus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Persists ingest run metadata into the corpus database.
 */
public final class IngestRunRepository {

    private IngestRunRepository() {
    }

    /**
     * Insert one ingest run record into the ingest_runs table.
     */
    public static void insertRun(
        Connection connection,
        String runId,
        String runType,
        String status,
        String startedAt,
        String completedAt,
        String notes
    ) throws SQLException {
        String sql = """
            INSERT INTO ingest_runs (run_id, run_type, status, started_at, completed_at, notes)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, runType);
            statement.setString(3, status);
            statement.setString(4, startedAt);
            statement.setString(5, completedAt);
            statement.setString(6, notes);
            statement.executeUpdate();
        }
    }
}
