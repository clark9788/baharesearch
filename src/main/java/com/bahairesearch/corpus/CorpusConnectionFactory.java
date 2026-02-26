package com.bahairesearch.corpus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Opens SQLite JDBC connections for corpus persistence.
 */
public final class CorpusConnectionFactory {

    private CorpusConnectionFactory() {
    }

    /**
     * Open a JDBC connection to the configured local corpus database.
     */
    public static Connection open(CorpusPaths corpusPaths) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + corpusPaths.databasePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 8000");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
        return connection;
    }
}
