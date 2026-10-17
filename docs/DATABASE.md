# Data and provenance

`knowledge/catalog.json` is the reviewed input manifest. `knowledge/schema.sql` defines the database. `python tools/build_knowledge.py` creates `app/src/main/assets/knowledge.db` and its SHA-256. Native startup verifies the checksum and SQLite version/integrity before enabling identity claims.

Tables: Metadata, Source, Medicine, Alias, Ingredient, MedicineIngredient, Interaction. Stable IDs are keys. Alias uniqueness is scoped to medicine because strengths share brands. Every composition and interaction references a source. Interaction pairs are stored in lexical ingredient-ID order. Strengths use decimal strings with explicit units; these are composition facts, not dose recommendations.

Current sources:

- [Pfizer VIAGRA label](https://labeling.pfizer.com/ShowLabeling.aspx?id=652), revision December 2017, sections 3/11: three labeled strengths; sildenafil is the canonical active moiety. The label describes the citrate salt.
- [DailyMed NITROSTAT label](https://dailymed.nlm.nih.gov/dailymed/lookup.cfm?setid=9a52da75-d0af-4237-96c0-0b12adfa8636), revision February 2023, sections 3/11 and 4.1/7.1: three strengths and the label's contraindication for the stored ingredient pair.
- [Manufacturer TYLENOL product page](https://www.tylenol.com/products/headache-pain-relief/tylenol-extra-strength-liquid-gels), unversioned page accessed September 21, 2026: the named liquid-gel formulation only.

The record status is `SOURCE_CHECKED_NOT_CLINICALLY_REVIEWED`. Access dates are recorded separately from source revisions. This subset is not exhaustive, not tailored to India and not a clinical validation claim. The source archive does not redistribute entire manufacturer labels. Recheck rights and current clinical evidence before expanding/releasing a production dataset.

A separate `regimen.db` contains Regimen and DoseEvent tables. Medication IDs, due instants, timezone IDs, statuses and recorded times are inside AES-GCM ciphertext. Occurrence IDs authenticate the payload as additional associated data. Non-exportable keys are in Android Keystore. Deletion removes the occurrence and its events in a transaction. A failed schema upgrade preserves existing records and fails explicitly; version 1 currently has no historical migrations.
