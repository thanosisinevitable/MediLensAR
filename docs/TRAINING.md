# IndiMed-AR dataset and training

No real capture dataset or checkpoint was supplied. No medicine-package detector was trained in this delivery. The PDF's claimed pilot results are not local measurements.

Preserved tooling supports a single `medicine_package` class, bounding boxes, medicine IDs, physical package-instance IDs, capture sessions, conditions and supported/open-set flags. See `tests/test_dataset.py` for manifest fixtures (synthetic metadata, not medical data). Capture boxes, strips, multiple packs, clutter, glare, blur, angles and lighting changes.

Never randomly split adjacent video frames. The splitter builds connected groups across capture sessions, physical package instances and exact image hashes. It holds unsupported medicines in `test_open_set` and requested unseen conditions in `test_conditions`. Near-duplicate perceptual auditing still requires real images; exact hashes alone do not prove independence.

```text
python tools/dataset.py captures/manifest.jsonl --root captures --split data/split.json --held-out-condition glare --yolo data/yolo
python tools/train.py train --data data/yolo/data.yaml
python tools/train.py evaluate --data data/yolo/data.yaml --weights runs/detector/weights/best.pt
python tools/train.py export --weights runs/detector/weights/best.pt
```

`configs/training.json` fixes seed, input size and other settings. Training refuses synthetic fixture metadata. Install optional dependencies from `tools/requirements-ml.txt` in a separate environment only when real data is available. Check the Ultralytics AGPL/commercial licensing terms before distributing models/products that depend on that training stack. It is not an Android runtime dependency in this build.

Export validates float32 NHWC input and a one-class `[1,5,N]` TFLite output contract. Quantization is not enabled without a quality evaluation. **Exported detector files are not automatically consumed by this native app yet.** The missing integration is a detector adapter plus frame-local package ROI/OCR mapping, not simply renaming a model file. Evaluate tracking and open-set identity errors after integration.
