package com.bahairesearch.corpus;

import com.bahairesearch.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Prepares local corpus directories and database schema for Phase 1.
 */
public final class CorpusBootstrapService {

    private static volatile boolean initialized;

    private CorpusBootstrapService() {
    }

    /**
     * Initialize corpus paths and schema once when auto-initialize is enabled.
     */
    public static void initializeIfEnabled(AppConfig appConfig) {
        if (!appConfig.corpusAutoInitialize() || initialized) {
            return;
        }

        synchronized (CorpusBootstrapService.class) {
            if (initialized) {
                return;
            }

            CorpusPaths corpusPaths = CorpusPaths.fromConfig(appConfig);
            try {
                createDirectories(corpusPaths);
                initializeDatabase(corpusPaths);
                writeBootstrapManifest(corpusPaths);
                initialized = true;
            } catch (IOException | SQLException exception) {
                throw new IllegalStateException("Failed to initialize local corpus storage.", exception);
            }
        }
    }

    private static void createDirectories(CorpusPaths corpusPaths) throws IOException {
        Files.createDirectories(corpusPaths.basePath());
        Files.createDirectories(corpusPaths.snapshotsPath());
        Files.createDirectories(corpusPaths.ingestPath());
    }

    private static void initializeDatabase(CorpusPaths corpusPaths) throws SQLException {
        try (Connection connection = CorpusConnectionFactory.open(corpusPaths)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }

            CorpusSchemaInitializer.initialize(connection);

            Instant now = Instant.now();
            String runId = "phase1-bootstrap-" + now.toEpochMilli();
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(now);
            IngestRunRepository.insertRun(
                connection,
                runId,
                "bootstrap",
                "success",
                timestamp,
                timestamp,
                "Initialized directories and schema for local corpus foundation."
            );
        }
    }

    private static void writeBootstrapManifest(CorpusPaths corpusPaths) throws IOException {
        Instant now = Instant.now();
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(now);
        String fileName = "phase1-bootstrap-" + now.toEpochMilli() + ".json";

        String manifest = """
            {
              "event": "phase1-bootstrap",
              "timestamp": "%s",
              "basePath": "%s",
              "databasePath": "%s",
              "snapshotsPath": "%s",
              "ingestPath": "%s"
            }
            """.formatted(
            timestamp,
            escapeJson(corpusPaths.basePath().toString()),
            escapeJson(corpusPaths.databasePath().toString()),
            escapeJson(corpusPaths.snapshotsPath().toString()),
            escapeJson(corpusPaths.ingestPath().toString())
        );

        Files.writeString(corpusPaths.ingestPath().resolve(fileName), manifest);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
