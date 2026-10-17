package org.medilens.core;

/**
 * Central runtime thresholds.
 *
 * Tuned for the stabilized OCR + tracker pipeline:
 *  - OCR runs often enough to confirm a label quickly without flooding ML Kit
 *  - short OCR misses do not instantly destroy a track
 *  - medicine identity still requires repeated agreement
 *  - fewer simultaneous tracks reduces visual clutter and false associations
 */
public final class Config {

    private Config() {}

    /**
     * Time between OCR requests.
     *
     * 400 ms gives roughly 2.5 OCR attempts/second, which is fast enough for
     * confirmation while avoiding the constantly-changing feel of very rapid OCR.
     */
    public static final long OCR_INTERVAL_MS = 400;

    /**
     * Maximum age of an OCR result before the GL thread ignores it.
     *
     * Allows for slower phones without accepting very stale camera readings.
     */
    public static final long MAX_RESULT_AGE_MS = 2200;

    /**
     * Absolute lifetime of a track before it can be discarded.
     *
     * Tracker.java applies its own shorter/longer visibility rules depending
     * on whether the label is unresolved, stabilizing, or confirmed.
     */
    public static final long TRACK_TTL_MS = 5000;

    /**
     * General freshness window for a tracked label.
     *
     * This now lines up with the temporary-miss grace periods used by
     * NativeScanner and Tracker so labels do not blink off immediately.
     */
    public static final long LABEL_FRESH_MS = 1800;

    /**
     * Number of consecutive resolver agreements required before a medicine is
     * considered confirmed.
     *
     * Keep this at 3. NativeScanner already filters raw OCR across multiple
     * frames before Tracker sees it, so lowering this would make false positive
     * medicine identification easier.
     */
    public static final int CONFIRMATIONS = 3;

    /**
     * Minimum IoU used as one part of track association.
     *
     * Tracker.java also considers center-point distance, so this can be a bit
     * more forgiving than the old value without causing identity transfer.
     */
    public static final float MIN_IOU = 0.12f;

    /**
     * Maximum number of simultaneous OCR/medicine tracks.
     *
     * A medicine scan normally needs only a handful of labels. Keeping this
     * low reduces screen clutter and random environmental text.
     */
    public static final int MAX_TRACKS = 5;

    /**
     * Prevent repeated safety-alert churn while the same medicine labels are
     * being refreshed.
     */
    public static final long ALERT_DEBOUNCE_MS = 900;

    /**
     * Dose timing windows. Unchanged.
     */
    public static final long DOSE_EARLY_SECONDS = 15 * 60;
    public static final long DOSE_LATE_SECONDS = 30 * 60;
}
