package com.bahairesearch.model;

import java.util.List;

/**
 * Represents final research output with summary and selected quotes.
 */
public record ResearchReport(String summary, List<QuoteResult> quotes) {
}