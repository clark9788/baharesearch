Current state: Every passage gets a simple p.1, p.2, p.3... sequential counter — purely positional, meaning nothing to a reader.

References.xlsx is your own planning doc — maps each of the 76 source files to a locator scheme — but no code reads it yet.

The locator types you described map roughly to these extraction strategies:

Your Type	What it means for extraction
Roman Numerals	Already embedded in text_content (e.g. – XLVII –) — extractable from the text
***	Section break only — no useful locator, probably just show the section marker
Nothing	No locator possible — omit or show "—"
compilation	Last line of passage IS the citation — already stored in text, just parse it
paragraph-by-title	Paragraphs numbered within a titled section — need to extract during ingest
number.paragraph	e.g. 3.12 — structured numbering, extract during ingest
number title paragraph	Consecutive paragraph numbers — extract during ingest
title and subscript date	Title + date — extract from document structure during ingest
The key question before planning: Are you thinking about:

A) Re-ingesting with better locator extraction (store richer locators in the DB), or

B) Pointing users to a location in the source file (like "paragraph 47" or "section XIV") without worrying about published page numbers, or
C) Both — store a semantic locator AND a file offset so users can open the file and jump there?

Also — for the Word files specifically, do you want something like "open in Word at paragraph X" as a link, or just a human-readable label like "Section XIV, ¶3"?