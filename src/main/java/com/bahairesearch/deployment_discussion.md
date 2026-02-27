let's talk deployment again. I'm getting close. I was thinking I would rather deploy with the title files rather than corpus.db*. My reasoning is that those who would use this program might appreciate having the bahai.org/library texts downloaded. They might use them to get the full quotes or look at context surrounding the quote. Do you see a downside to that approach? I could set corpus.autoIngestIfEmpty = true by default. Thoughts?

I think your idea is strong, and for your audience it can be a good default — __with one adjustment__.

## Short answer

Yes, you can deploy with curated source/title files instead of shipping `corpus.db*`, but I’d recommend:

- ship curated files (`data/corpus/curated/en/**`)
- set `corpus.autoIngestIfEmpty=true`
- __also set `corpus.curatedIngestEnabled=true`__
- keep `corpus.forceReingest=false`

That gives first-run DB build from local authenticated files, and users also have the full documents for context.

## Main downsides to be aware of

1. __First-run delay__

- DB build may take noticeable time (minutes depending on machine/disk).

2. __Parsing variability risk__

- If ingest/parser logic changes between versions, first-run DB shape can differ by app version.
- (Still deterministic for same version + same source files.)

3. __More moving parts at startup__

- If manifest/path/file mismatch exists, startup can fail before search works.

4. __Distribution size increases__

- Shipping many DOCX/PDF/HTML files is larger than shipping one DB snapshot.

## Practical recommendation (best of both)

Offer two release flavors:

- __Standard users:__ prebuilt `corpus.db` (fast start, fewer support issues)
- __Research/archive users:__ curated source package with auto-ingest enabled

If you only want one package, your proposed approach is viable — just include a clear “First launch builds local index” message in docs/UI.

If you want, I can draft the exact production `bahai-research.properties` profile for this “curated-first deployment” mode.
