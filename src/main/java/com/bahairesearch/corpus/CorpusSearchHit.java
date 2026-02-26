package com.bahairesearch.corpus;

/**
 * Represents one local corpus search result with citation metadata.
 */
public record CorpusSearchHit(
    String quote,
    String author,
    String title,
    String locator,
    String sourceUrl,
    double score
) {
}
