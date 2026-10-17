# Native runtime

`MainActivity` owns permissions, AR Services installation, activity lifecycle and native screens. `NativeScanner` owns the render thread, camera image scheduling, OCR result generations, tracker, safety engine and temporary anchors. `CameraBackground` draws ARCore's camera texture. `ScanOverlay` draws immutable snapshots on the Android canvas; world anchors are projected through the current camera matrix.

An OCR request captures the CPU image dimensions, orientation, camera pose, monotonic timestamp and IMAGE_PIXELS→VIEW_NORMALIZED transform from the **same frame**, before asynchronous recognition. OCR coordinates are rotated back into the raw image frame before applying that captured transform. A result is discarded when the session generation changes, it ages out or the camera moved too far while it was processed. The camera image is closed in the task completion listener; only one OCR request is in flight.

Only the GL thread mutates tracks and anchors. The main thread receives immutable label and alert snapshots. No ARCore Frame object crosses to asynchronous callbacks. Knowledge is loaded before scanning begins, and user records are accessed by the UI layer.

| Module | Input → output |
|---|---|
| Resolver | OCR block → exact candidate or explicit unresolved reason |
| Tracker | Regions + timestamp → stable temporary IDs and repeated identity evidence |
| SafetyEngine | Confident visible tracks → duplicate, documented interaction or NO DATA alerts |
| AlertManager | Current alert set → deduplicated/debounced alert set; removed evidence expires immediately |
| DoseEngine | User-entered instant + event → deterministic dose state |
| KnowledgeStore | Asset checksum/schema → read-only medicine, ingredient and evidence records |
| RegimenStore | User occurrence/event → encrypted SQLite payload |
| ScanOverlay | Immutable snapshot + dose labels → camera-aligned labels and relationships |

The current text-block baseline is not the final detector/crop pipeline. Its strict matching is intentionally unsuitable for claiming broad recognition coverage. A detector integration must replace region generation, retain capture-frame geometry, and pass independent open-set and association tests before it changes user-facing confidence.

There is no normal-operation network dependency in the app manifest. AR Services and dependency/model packaging are provisioned before the airplane-mode validation. There is no cloud-anchor API, Python inference server or persistent cabinet anchor.
