package com.bahairesearch.corpus;

import com.bahairesearch.config.AppConfig;

import java.nio.file.Path;

/**
 * Resolves canonical local paths used by the corpus subsystem.
 */
public record CorpusPaths(
    Path basePath,
    Path databasePath,
    Path snapshotsPath,
    Path ingestPath
) {

    /**
     * Build corpus paths from application configuration values.
     */
    public static CorpusPaths fromConfig(AppConfig appConfig) {
        Path configuredBase = Path.of(appConfig.corpusBasePath());
        Path basePath = configuredBase.isAbsolute()
            ? configuredBase.normalize()
            : Path.of("").toAbsolutePath().resolve(configuredBase).normalize();

        Path databasePath = basePath.resolve(appConfig.corpusDatabaseFileName()).normalize();
        Path snapshotsPath = basePath.resolve(appConfig.corpusSnapshotsDirName()).normalize();
        Path ingestPath = basePath.resolve(appConfig.corpusIngestDirName()).normalize();

        return new CorpusPaths(basePath, databasePath, snapshotsPath, ingestPath);
    }
}
