package com.bahairesearch.corpus;

/**
 * A passage extracted from a source file, paired with its locator (anchor ID for xhtml,
 * empty string for docx/pdf — falls back to sequential "p.N" on insert).
 */
record ExtractedPassage(String text, String locator) {}
