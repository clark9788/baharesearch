package com.bahairesearch.model;

/**
 * Represents one quote selected for final output.
 */
public record QuoteResult(
    String quote,
    String author,
    String bookTitle,
    String paragraphOrPage,
    String sourceUrl
) {
}