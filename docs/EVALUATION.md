# Evaluation report

Actual local results: 61 Java specifications pass; 49 Python tests pass; 16 Java files parse with zero syntax errors; SQLite integrity and references pass. Exact console logs are in `evaluation/local-verification.txt`. These are software tests, not measured medical recognition accuracy.

| Evaluation | Result |
|---|---|
| Android APK build | NOT VERIFIED — bootstrap did not complete; SDK absent |
| UI rendering and usability on phone | NOT YET MEASURED |
| Camera/OCR coordinate alignment | Pure coordinate-function tests pass; phone mapping NOT YET MEASURED |
| Package detector mAP/latency | NOT YET MEASURED — no trained detector |
| Known-package identification | NOT YET MEASURED |
| False confident open-set identification rate | NOT YET MEASURED |
| Track ID switches on real video | NOT YET MEASURED |
| OCR/resolution/reasoning/AR P50/P95 on phone | NOT YET MEASURED |
| End-to-end time to confirmed identity | NOT YET MEASURED |
| Airplane-mode operation | Configured; NOT YET VERIFIED on device |

The PDF's performance numbers are targets/prior reported work, not results of this repository. The configured 700 ms OCR cadence and 3-reading consensus are engineering defaults awaiting device calibration.

`tools/evaluate.py` accepts JSONL records for resolution, latency and track observations. It calculates known identification rate, unsupported false-confidence rate, within-session ID switches and P50/P95. Empty datasets remain NOT YET MEASURED. Zero observed open-set errors include a one-sided upper confidence bound; they do not prove zero risk.

Physical test gates: verify portrait/rotated input, screen crop boundaries, lighting changes, three separate supported packs, crossing/overlap, moving camera, moving package, covering/removing labels, stale result rejection, background/resume, permission denial/recovery, AR Services missing, depth unavailable fallback, corrupted database, inaccessible Keystore, saved events, DST boundaries and network disabled. Use independent capture sessions and package instances. Record the phone model, OS, AR Services version, app commit, knowledge checksum and dataset split.
