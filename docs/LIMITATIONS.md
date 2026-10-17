# Known limitations

- No Android SDK compilation, APK or device rendering validation occurred here. Integration/API mistakes may remain despite passing core tests and syntax parsing.
- OCR blocks are not a package detector. Multiple text blocks from the same pack can appear separately; identity text on different panels will not combine. The native implementation deliberately lacks whole-scene fuzzy matching.
- The knowledge asset now contains 32 formulations, including selected Indian-market brands, but it is still far from comprehensive. A matching rule cannot authenticate packaging or rule out unrecognized regional variants/counterfeits. Interaction coverage remains much smaller than recognition coverage.
- Source checking is not qualified clinical review. There is one stored interaction pair. The app cannot establish that an unlisted pair is safe.
- Three repeated reads do not make errors independent; rule-based HIGH confidence is not a calibrated probability. Independent open-set evaluation is a release blocker.
- IoU tracking assumes limited motion between sparse OCR updates. It is not a validated object tracker and can lose identity on crossings/occlusion. The current conservative behavior resets confidence and expires labels.
- Depth anchors are short-lived approximate point attachments. The app does not estimate a medicine package's 6DoF pose, persist anchors across sessions or prove a depth hit belongs to the package surface. Fallback uses explicitly marked screen labels.
- User plans contain dated occurrences only. No repeated schedule generator, dosage quantity advice, background reminders, notification delivery or medication adherence guarantee exists.
- There is no GNN, cloud sync, telemetry uploader or alternate web app. Optional training tooling is not a bundled model.
- Native accessibility exposes scan results through standard buttons/sheets; custom camera overlays themselves are excluded from TalkBack. Device font scaling, small-screen overlap and large-text UX require physical validation.
- First build/provisioning needs internet and SDK license acceptance. Offline app operation is configured, but the final airplane-mode test remains pending.
