package org.medilens.scan;

import android.app.Activity;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraAccessException;
import android.media.Image;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;

import com.google.ar.core.Anchor;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.medilens.core.Config;
import org.medilens.core.Coordinates;
import org.medilens.core.Resolver;
import org.medilens.core.SafetyEngine;
import org.medilens.core.Tracker;

import org.medilens.core.Domain.Box;
import org.medilens.core.Domain.Interaction;
import org.medilens.core.Domain.Medicine;
import org.medilens.core.Domain.Observation;
import org.medilens.core.Domain.Phase;
import org.medilens.core.Domain.Track;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;


/**
 * Native AR + OCR scanner.
 *
 * Main goals:
 *
 * 1. ARCore owns the camera.
 * 2. Only one OCR request runs at a time.
 * 3. Garbage OCR is filtered before it ever reaches Tracker.
 * 4. Text must remain reasonably stable across OCR passes.
 * 5. Temporary OCR misses do not immediately remove labels.
 * 6. Temporary AR tracking loss does not instantly wipe the UI.
 */
public final class NativeScanner implements GLSurfaceView.Renderer {

    public interface Listener {
        void onSnapshot(ScanSnapshot snapshot);
    }

    /*
     * OCR stability.
     *
     * Two OCR confirmations are required before unsupported/raw medicine-looking
     * text is promoted into Tracker.
     */
    private static final int REQUIRED_STABLE_HITS = 2;

    /*
     * Keep a recognized OCR candidate alive briefly if ML Kit misses it on
     * one or two frames.
     */
    private static final long OCR_MEMORY_MS = 1800L;

    /*
     * Candidates older than this are completely forgotten.
     */
    private static final long OCR_FORGET_MS = 3200L;

    /*
     * Brief ARCore PAUSED states are common on handheld phones.
     * Do not immediately destroy all overlays.
     */
    private static final long AR_TRACKING_GRACE_MS = 1600L;

    /*
     * Maximum number of medicine-ish OCR candidates that can reach Tracker.
     * Prevents a busy medicine package from filling the screen with cards.
     */
    private static final int MAX_STABLE_OBSERVATIONS = 5;

    /*
     * Reject tiny line boxes. Tiny OCR regions are one of the biggest sources
     * of changing gibberish.
     */
    private static final float MIN_LINE_WIDTH = 0.018f;
    private static final float MIN_LINE_HEIGHT = 0.010f;
    private static final float MIN_LINE_AREA = 0.00030f;

    /*
     * Medicine package text clues.
     *
     * This is not used to IDENTIFY a medicine.
     * It only decides whether unsupported OCR is worth displaying/tracking.
     */
    private static final Pattern DOSE_PATTERN = Pattern.compile(
            "\\b\\d+(?:\\.\\d+)?\\s*(?:MG|MCG|µG|UG|G|ML|IU|I\\.U\\.|%|MG/ML|MCG/ML)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DOSAGE_FORM_PATTERN = Pattern.compile(
            "\\b(?:TABLET|TABLETS|TAB|TABS|CAPSULE|CAPSULES|CAP|CAPS|SYRUP|" +
                    "SUSPENSION|INJECTION|INJ|CREAM|OINTMENT|GEL|DROPS|SPRAY|" +
                    "SACHET|POWDER|SOLUTION|LOTION|INHALER|PATCH|IP|USP|BP)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final Activity activity;
    private final Listener listener;

    private final Resolver resolver;
    private final Tracker tracker;
    private final SafetyEngine safety;

    private final SafetyEngine.AlertManager alerts =
            new SafetyEngine.AlertManager();

    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    private final CameraBackground background =
            new CameraBackground();

    private final AtomicBoolean busy =
            new AtomicBoolean(false);

    private final AtomicInteger generation =
            new AtomicInteger();

    private final AtomicReference<Result> pending =
            new AtomicReference<>();

    private final Map<Long, Anchor> anchors =
            new HashMap<>();

    /*
     * OCR temporal memory.
     *
     * We maintain this separately from Tracker because Tracker should not have
     * to deal with constantly changing low-quality ML Kit strings.
     */
    private final Object temporalLock =
            new Object();

    private final List<StableCandidate> stableCandidates =
            new ArrayList<>();

    private volatile Session session;

    private volatile boolean scanning = true;

    private volatile String ocrError = "";

    private boolean rendererReady = false;

    private int width = 1;
    private int height = 1;

    private int rotation = -1;
    private int sensorRotation = 90;

    private long lastOcr = 0;
    private long lastUi = 0;

    private volatile long ocrMs = 0;

    private int clearedGeneration = -1;

    private long trackingLostAt = 0;
    private long lastCandidateSeenAt = 0;


    private static final class Result {

        final List<Observation> observations;
        final long time;
        final Pose pose;
        final int generation;
        final int rawCandidateCount;

        Result(
                List<Observation> observations,
                long time,
                Pose pose,
                int generation,
                int rawCandidateCount
        ) {
            this.observations = observations;
            this.time = time;
            this.pose = pose;
            this.generation = generation;
            this.rawCandidateCount = rawCandidateCount;
        }
    }


    private static final class OcrBatch {

        final List<Observation> observations;
        final int rawCandidateCount;

        OcrBatch(
                List<Observation> observations,
                int rawCandidateCount
        ) {
            this.observations = observations;
            this.rawCandidateCount = rawCandidateCount;
        }
    }


    private static final class Piece {

        final Box box;
        final String text;

        Piece(Box box, String text) {
            this.box = box;
            this.text = text;
        }
    }


    private static final class OcrCandidate {

        final Box box;
        final String text;
        final String key;
        final boolean resolved;
        final float quality;

        OcrCandidate(
                Box box,
                String text,
                String key,
                boolean resolved,
                float quality
        ) {
            this.box = box;
            this.text = text;
            this.key = key;
            this.resolved = resolved;
            this.quality = quality;
        }
    }


    private static final class StableCandidate {

        Box box;

        String text;
        String key;

        boolean resolved;

        float quality;

        int hits;

        long firstSeen;
        long lastSeen;

        StableCandidate(
                OcrCandidate source,
                long now
        ) {
            box = source.box;

            text = source.text;
            key = source.key;

            resolved = source.resolved;

            quality = source.quality;

            hits = 1;

            firstSeen = now;
            lastSeen = now;
        }
    }


    public NativeScanner(
            Activity activity,
            List<Medicine> catalog,
            List<Interaction> interactions,
            Listener listener
    ) {

        this.activity = activity;
        this.listener = listener;

        resolver = new Resolver(catalog);
        tracker = new Tracker(resolver);
        safety = new SafetyEngine(interactions);
    }


    public void attach(Session value)
            throws CameraAccessException {

        session = value;

        generation.incrementAndGet();

        clearTemporal();

        CameraManager manager =
                (CameraManager) activity.getSystemService(
                        Activity.CAMERA_SERVICE
                );

        Integer degrees =
                manager
                        .getCameraCharacteristics(
                                value
                                        .getCameraConfig()
                                        .getCameraId()
                        )
                        .get(
                                CameraCharacteristics.SENSOR_ORIENTATION
                        );

        if (degrees == null) {
            throw new IllegalStateException(
                    "Camera orientation unavailable"
            );
        }

        sensorRotation = degrees;
    }


    public void invalidate() {

        generation.incrementAndGet();

        pending.set(null);

        clearTemporal();
    }


    public void setScanning(boolean value) {

        scanning = value;

        invalidate();
    }


    public void close() {

        invalidate();

        recognizer.close();

        session = null;
    }


    public void releaseAnchors() {

        detachAnchorsOnly();

        tracker.clear();

        alerts.clear();

        clearTemporal();

        trackingLostAt = 0;
    }


    private void detachAnchorsOnly() {

        for (Anchor anchor : anchors.values()) {

            try {
                anchor.detach();
            } catch (RuntimeException ignored) {
                // ARCore session may already be closing.
            }
        }

        anchors.clear();
    }


    private void clearTemporal() {

        synchronized (temporalLock) {
            stableCandidates.clear();
        }

        lastCandidateSeenAt = 0;
    }


    @Override
    public void onSurfaceCreated(
            GL10 gl,
            EGLConfig config
    ) {

        try {

            background.create();

            rendererReady = true;

            rotation = -1;

        } catch (RuntimeException e) {

            rendererReady = false;

            Diagnostics.log(
                    Diagnostics.Level.ERROR,
                    "RENDERER_UNAVAILABLE",
                    e
            );

            listener.onSnapshot(
                    ScanSnapshot.empty(
                            "Camera renderer unavailable"
                    )
            );
        }
    }


    @Override
    public void onSurfaceChanged(
            GL10 gl,
            int w,
            int h
    ) {

        width = w;
        height = h;

        GLES20.glViewport(
                0,
                0,
                w,
                h
        );

        rotation = -1;

        invalidate();
    }


    @Override
    public void onDrawFrame(GL10 gl) {

        GLES20.glClearColor(
                .035f,
                .09f,
                .10f,
                1f
        );

        GLES20.glClear(
                GLES20.GL_COLOR_BUFFER_BIT |
                        GLES20.GL_DEPTH_BUFFER_BIT
        );

        Session currentSession = session;

        if (
                currentSession == null ||
                        !rendererReady
        ) {
            return;
        }

        try {

            int gen = generation.get();

            if (clearedGeneration != gen) {

                releaseAnchors();

                clearedGeneration = gen;
            }

            int displayRotation =
                    activity
                            .getWindowManager()
                            .getDefaultDisplay()
                            .getRotation();

            if (displayRotation != rotation) {

                rotation = displayRotation;

                currentSession.setDisplayGeometry(
                        rotation,
                        width,
                        height
                );

                /*
                 * Existing AR anchors are invalid for the old display geometry.
                 * OCR temporal state is allowed to rebuild cleanly.
                 */
                releaseAnchors();
            }

            currentSession.setCameraTextureName(
                    background.texture()
            );

            Frame frame =
                    currentSession.update();

            background.draw(frame);

            long now =
                    SystemClock.elapsedRealtime();

            com.google.ar.core.Camera camera =
                    frame.getCamera();

            if (!scanning) {

                publish(
                        ScanSnapshot.empty(
                                "Scanning paused"
                        ),
                        now
                );

                return;
            }

            /*
             * Do NOT instantly clear everything when ARCore reports PAUSED.
             *
             * A short PAUSED state can happen because of autofocus,
             * device motion, lighting, or ARCore relocalisation.
             */
            if (
                    camera.getTrackingState() !=
                            TrackingState.TRACKING
            ) {

                if (trackingLostAt == 0) {
                    trackingLostAt = now;
                }

                if (
                        now - trackingLostAt >
                                AR_TRACKING_GRACE_MS
                ) {
                    detachAnchorsOnly();
                }

                List<Track> existingTracks =
                        tracker.current(now);

                if (!existingTracks.isEmpty()) {

                    publish(
                            buildScreenSpaceSnapshot(
                                    existingTracks,
                                    now,
                                    "Hold steady · tracking camera"
                            ),
                            now
                    );

                } else {

                    publish(
                            ScanSnapshot.empty(
                                    "Hold steady · tracking camera"
                            ),
                            now
                    );
                }

                return;
            }

            trackingLostAt = 0;

            Result result =
                    pending.getAndSet(null);

            boolean refreshed = false;

            if (
                    result != null &&
                            result.generation == gen &&
                            now - result.time <=
                                    Config.MAX_RESULT_AGE_MS &&
                            closePose(
                                    result.pose,
                                    camera.getPose()
                            )
            ) {

                if (
                        result.rawCandidateCount > 0
                ) {
                    lastCandidateSeenAt = now;
                }

                tracker.update(
                        result.observations,
                        now
                );

                refreshed = true;
            }

            List<Track> tracks =
                    tracker.current(now);

            Set<Long> visible =
                    new HashSet<>();

            float[] view =
                    new float[16];

            float[] projection =
                    new float[16];

            float[] vp =
                    new float[16];

            camera.getViewMatrix(
                    view,
                    0
            );

            camera.getProjectionMatrix(
                    projection,
                    0,
                    .05f,
                    20f
            );

            Matrix.multiplyMM(
                    vp,
                    0,
                    projection,
                    0,
                    view,
                    0
            );

            List<ScanSnapshot.Label> labels =
                    new ArrayList<>();

            List<Track> shownTracks =
                    new ArrayList<>();

            for (Track track : tracks) {

                visible.add(track.id);

                Anchor anchor =
                        anchors.get(track.id);

                float[] point =
                        anchor == null
                                ? null
                                : project(
                                anchor,
                                vp
                        );

                /*
                 * If an old anchor has drifted far away from the current
                 * tracked OCR label, throw away that anchor.
                 */
                if (
                        anchor != null &&
                                (
                                        point == null ||
                                                (
                                                        refreshed &&
                                                                Math.hypot(
                                                                        point[0] -
                                                                                track.box.cx(),
                                                                        point[1] -
                                                                                track.box.cy()
                                                                ) > .10
                                                )
                                )
                ) {

                    try {
                        anchor.detach();
                    } catch (RuntimeException ignored) {
                    }

                    anchors.remove(
                            track.id
                    );

                    anchor = null;
                    point = null;
                }

                /*
                 * Only create a 3D anchor once Tracker has actually resolved
                 * the candidate into a supported medicine.
                 */
                if (
                        anchor == null &&
                                refreshed &&
                                track.phase == Phase.RESOLVED
                ) {

                    for (
                            HitResult hit :
                            frame.hitTest(
                                    track.box.cx() *
                                            width,
                                    track.box.cy() *
                                            height
                            )
                    ) {

                        if (
                                hit.getTrackable()
                                        instanceof DepthPoint &&
                                        hit.getDistance() >
                                                .12f &&
                                        hit.getDistance() <
                                                2.5f
                        ) {

                            anchor =
                                    hit.createAnchor();

                            anchors.put(
                                    track.id,
                                    anchor
                            );

                            point =
                                    project(
                                            anchor,
                                            vp
                                    );

                            break;
                        }
                    }
                }

                Box labelBox =
                        track.box;

                /*
                 * Smooth AR projection.
                 *
                 * If there is no reliable 3D point we intentionally keep the
                 * label attached to the stabilized 2D OCR box.
                 */
                if (point != null) {

                    float dx =
                            point[0] -
                                    track.box.cx();

                    float dy =
                            point[1] -
                                    track.box.cy();

                    labelBox =
                            new Box(
                                    track.box.l + dx,
                                    track.box.t + dy,
                                    track.box.r + dx,
                                    track.box.b + dy
                            );
                }

                shownTracks.add(
                        track
                );

                labels.add(
                        new ScanSnapshot.Label(
                                track.id,
                                labelBox,

                                point == null
                                        ? track.box.cx()
                                        : point[0],

                                point == null
                                        ? track.box.cy()
                                        : point[1],

                                point != null,

                                track.match,
                                track.phase,
                                track.provisional,
                                track.text
                        )
                );
            }

            /*
             * Remove anchors belonging to tracks that no longer exist.
             */
            Iterator<Map.Entry<Long, Anchor>>
                    iterator =
                    anchors
                            .entrySet()
                            .iterator();

            while (iterator.hasNext()) {

                Map.Entry<Long, Anchor> entry =
                        iterator.next();

                if (
                        !visible.contains(
                                entry.getKey()
                        )
                ) {

                    try {
                        entry
                                .getValue()
                                .detach();
                    } catch (RuntimeException ignored) {
                    }

                    iterator.remove();
                }
            }

            String status;

            if (!ocrError.isEmpty()) {

                status = ocrError;

            } else if (!labels.isEmpty()) {

                status =
                        "Live · " +
                                labels.size() +
                                " medicine label" +
                                (
                                        labels.size() == 1
                                                ? ""
                                                : "s"
                                );

            } else if (
                    now -
                            lastCandidateSeenAt <
                            1600L
            ) {

                status =
                        "Reading medicine label · hold steady";

            } else {

                status =
                        "Point at the medicine name + strength";
            }

            publish(
                    new ScanSnapshot(
                            labels,
                            alerts.update(
                                    safety.evaluate(
                                            shownTracks
                                    ),
                                    now
                            ),
                            status,
                            ocrMs
                    ),
                    now
            );

            /*
             * Start OCR only when the previous OCR call has completed.
             */
            if (
                    now - lastOcr >=
                            Config.OCR_INTERVAL_MS &&
                            busy.compareAndSet(
                                    false,
                                    true
                            )
            ) {

                lastOcr = now;

                acquire(
                        frame,
                        now,
                        gen
                );
            }

        } catch (Exception e) {

            Diagnostics.log(
                    Diagnostics.Level.ERROR,
                    "AR_UNAVAILABLE",
                    e
            );

            detachAnchorsOnly();

            publish(
                    ScanSnapshot.empty(
                            "Camera or AR unavailable · tap Retry"
                    ),
                    SystemClock.elapsedRealtime()
            );
        }
    }


    private ScanSnapshot buildScreenSpaceSnapshot(
            List<Track> tracks,
            long now,
            String status
    ) {

        List<ScanSnapshot.Label> labels =
                new ArrayList<>();

        List<Track> shown =
                new ArrayList<>();

        for (Track track : tracks) {

            shown.add(track);

            labels.add(
                    new ScanSnapshot.Label(
                            track.id,
                            track.box,
                            track.box.cx(),
                            track.box.cy(),
                            false,
                            track.match,
                            track.phase,
                            track.provisional,
                            track.text
                    )
            );
        }

        return new ScanSnapshot(
                labels,
                alerts.update(
                        safety.evaluate(shown),
                        now
                ),
                status,
                ocrMs
        );
    }


    private void publish(
            ScanSnapshot snapshot,
            long now
    ) {

        /*
         * About 20 UI updates per second is more than enough.
         */
        if (
                now - lastUi <
                        50
        ) {
            return;
        }

        lastUi = now;

        listener.onSnapshot(
                snapshot
        );
    }


    /*
     * OCR results are allowed to survive slightly more camera movement than
     * before. The old threshold could discard perfectly usable OCR while the
     * user was holding the phone naturally.
     */
    private static boolean closePose(
            Pose a,
            Pose b
    ) {

        float distance =
                (float) Math.sqrt(
                        Math.pow(
                                a.tx() -
                                        b.tx(),
                                2
                        ) +
                                Math.pow(
                                        a.ty() -
                                                b.ty(),
                                        2
                                ) +
                                Math.pow(
                                        a.tz() -
                                                b.tz(),
                                        2
                                )
                );

        float[] x =
                a.getRotationQuaternion();

        float[] y =
                b.getRotationQuaternion();

        float dot =
                Math.abs(
                        x[0] * y[0] +
                                x[1] * y[1] +
                                x[2] * y[2] +
                                x[3] * y[3]
                );

        return distance < .22f &&
                dot > .970f;
    }


    private static float[] project(
            Anchor anchor,
            float[] vp
    ) {

        if (
                anchor.getTrackingState() !=
                        TrackingState.TRACKING
        ) {
            return null;
        }

        Pose pose =
                anchor.getPose();

        float[] out =
                new float[4];

        Matrix.multiplyMV(
                out,
                0,
                vp,
                0,
                new float[]{
                        pose.tx(),
                        pose.ty(),
                        pose.tz(),
                        1
                },
                0
        );

        if (
                out[3] <= 0
        ) {
            return null;
        }

        float x =
                out[0] /
                        out[3] *
                        .5f +
                        .5f;

        float y =
                .5f -
                        out[1] /
                                out[3] *
                                .5f;

        if (
                x < 0 ||
                        x > 1 ||
                        y < 0 ||
                        y > 1
        ) {
            return null;
        }

        return new float[]{
                x,
                y
        };
    }


    private void acquire(
            Frame frame,
            long now,
            int gen
    ) {

        Image acquired = null;

        try {

            acquired =
                    frame.acquireCameraImage();

            final Image image =
                    acquired;

            int w =
                    image.getWidth();

            int h =
                    image.getHeight();

            int degrees =
                    (
                            sensorRotation -
                                    rotation *
                                            90 +
                                    360
                    ) % 360;

            float[] mapping =
                    new float[6];

            frame.transformCoordinates2d(
                    Coordinates2d.IMAGE_PIXELS,
                    new float[]{
                            0,
                            0,

                            w,
                            0,

                            0,
                            h
                    },
                    Coordinates2d.VIEW_NORMALIZED,
                    mapping
            );

            Pose pose =
                    frame
                            .getCamera()
                            .getPose();

            recognizer
                    .process(
                            InputImage.fromMediaImage(
                                    image,
                                    degrees
                            )
                    )
                    .addOnSuccessListener(
                            text -> {

                                if (
                                        gen !=
                                                generation.get()
                                ) {
                                    return;
                                }

                                OcrBatch batch =
                                        observations(
                                                text,
                                                w,
                                                h,
                                                degrees,
                                                mapping,
                                                now
                                        );

                                pending.set(
                                        new Result(
                                                batch.observations,
                                                now,
                                                pose,
                                                gen,
                                                batch.rawCandidateCount
                                        )
                                );

                                ocrMs =
                                        SystemClock
                                                .elapsedRealtime() -
                                                now;

                                ocrError = "";
                            }
                    )
                    .addOnFailureListener(
                            error -> {

                                Diagnostics.log(
                                        Diagnostics.Level.WARN,
                                        "OCR_UNAVAILABLE",
                                        error
                                );

                                if (
                                        gen ==
                                                generation.get()
                                ) {

                                    ocrError =
                                            "Text recognition unavailable · try again";
                                }
                            }
                    )
                    .addOnCompleteListener(
                            task -> {

                                image.close();

                                busy.set(
                                        false
                                );
                            }
                    );

        } catch (
                NotYetAvailableException e
        ) {

            if (
                    acquired != null
            ) {
                acquired.close();
            }

            busy.set(
                    false
            );

        } catch (Exception e) {

            if (
                    acquired != null
            ) {
                acquired.close();
            }

            busy.set(
                    false
            );

            ocrError =
                    "Text recognition unavailable";
        }
    }


    /**
     * Convert ML Kit output into a SMALL number of high-quality,
     * temporally stable medicine-label observations.
     */
    private OcrBatch observations(
            Text text,
            int w,
            int h,
            int imageRotation,
            float[] affine,
            long now
    ) {

        List<Piece> pieces =
                new ArrayList<>();

        /*
         * Use ML Kit LINES instead of entire blocks.
         *
         * Blocks can jump dramatically between OCR passes because ML Kit may
         * regroup several printed lines differently from frame to frame.
         *
         * Lines are much more spatially stable.
         */
        for (
                Text.TextBlock block :
                text.getTextBlocks()
        ) {

            for (
                    Text.Line line :
                    block.getLines()
            ) {

                Rect rect =
                        line.getBoundingBox();

                String value =
                        cleanText(
                                line.getText()
                        );

                if (
                        rect == null ||
                                !isSaneText(value)
                ) {
                    continue;
                }

                Box box =
                        mapRect(
                                rect,
                                w,
                                h,
                                imageRotation,
                                affine
                        );

                if (
                        box == null
                ) {
                    continue;
                }

                float boxWidth =
                        box.r -
                                box.l;

                float boxHeight =
                        box.b -
                                box.t;

                if (
                        boxWidth <
                                MIN_LINE_WIDTH ||
                                boxHeight <
                                        MIN_LINE_HEIGHT ||
                                box.area() <
                                        MIN_LINE_AREA
                ) {
                    continue;
                }

                /*
                 * Huge OCR boxes usually represent a badly grouped region,
                 * glare, or camera noise.
                 */
                if (
                        boxWidth > .92f ||
                                boxHeight > .45f
                ) {
                    continue;
                }

                pieces.add(
                        new Piece(
                                box,
                                value
                        )
                );
            }
        }

        /*
         * Reading order helps produce much more stable combined text.
         */
        Collections.sort(
                pieces,
                (a, b) -> {

                    float dy =
                            a.box.t -
                                    b.box.t;

                    if (
                            Math.abs(dy) >
                                    .025f
                    ) {
                        return dy < 0
                                ? -1
                                : 1;
                    }

                    return Float.compare(
                            a.box.l,
                            b.box.l
                    );
                }
        );

        /*
         * Remove almost-identical duplicate OCR lines.
         */
        pieces =
                deduplicatePieces(
                        pieces
                );

        List<OcrCandidate> rawCandidates =
                buildCandidates(
                        pieces
                );

        List<Observation> stable =
                stabilizeCandidates(
                        rawCandidates,
                        now
                );

        return new OcrBatch(
                stable,
                rawCandidates.size()
        );
    }


    private List<Piece> deduplicatePieces(
            List<Piece> pieces
    ) {

        List<Piece> result =
                new ArrayList<>();

        for (Piece piece : pieces) {

            boolean duplicate =
                    false;

            String key =
                    canonical(
                            piece.text
                    );

            for (
                    Piece existing :
                    result
            ) {

                if (
                        iou(
                                piece.box,
                                existing.box
                        ) > .65f &&
                                textSimilarity(
                                        key,
                                        canonical(
                                                existing.text
                                        )
                                ) > .72f
                ) {

                    duplicate = true;

                    break;
                }
            }

            if (!duplicate) {

                result.add(
                        piece
                );
            }
        }

        return result;
    }


    private List<OcrCandidate> buildCandidates(
            List<Piece> pieces
    ) {

        List<OcrCandidate> result =
                new ArrayList<>();

        boolean[] consumed =
                new boolean[
                        pieces.size()
                        ];

        /*
         * Greedy spatial clustering.
         *
         * Nearby lines on the same package face become one candidate such as:
         *
         * DOLO-650
         * PARACETAMOL TABLETS IP
         * 650 MG
         *
         * rather than three completely independent flickering OCR labels.
         */
        for (
                int i = 0;
                i < pieces.size();
                i++
        ) {

            if (
                    consumed[i]
            ) {
                continue;
            }

            Piece seed =
                    pieces.get(i);

            List<Integer> clusterIds =
                    new ArrayList<>();

            clusterIds.add(i);

            Box clusterBox =
                    seed.box;

            boolean added;

            do {

                added = false;

                int bestIndex = -1;

                float bestScore =
                        Float.MAX_VALUE;

                for (
                        int j = 0;
                        j < pieces.size();
                        j++
                ) {

                    if (
                            j == i ||
                                    consumed[j] ||
                                    clusterIds.contains(j)
                    ) {
                        continue;
                    }

                    Piece candidate =
                            pieces.get(j);

                    if (
                            !nearby(
                                    clusterBox,
                                    candidate.box
                            )
                    ) {
                        continue;
                    }

                    Box joined =
                            union(
                                    clusterBox,
                                    candidate.box
                            );

                    float width =
                            joined.r -
                                    joined.l;

                    float height =
                            joined.b -
                                    joined.t;

                    if (
                            width > .88f ||
                                    height > .48f ||
                                    joined.area() >
                                            .30f
                    ) {
                        continue;
                    }

                    float dx =
                            Math.abs(
                                    clusterBox.cx() -
                                            candidate.box.cx()
                            );

                    float dy =
                            Math.abs(
                                    clusterBox.cy() -
                                            candidate.box.cy()
                            );

                    float score =
                            dx * .35f +
                                    dy;

                    if (
                            score <
                                    bestScore
                    ) {

                        bestScore =
                                score;

                        bestIndex =
                                j;
                    }
                }

                if (
                        bestIndex >= 0 &&
                                clusterIds.size() < 6
                ) {

                    clusterIds.add(
                            bestIndex
                    );

                    clusterBox =
                            union(
                                    clusterBox,
                                    pieces
                                            .get(bestIndex)
                                            .box
                            );

                    added = true;
                }

            } while (added);

            /*
             * Sort text by physical reading position.
             */
            List<Piece> clusterPieces =
                    new ArrayList<>();

            for (
                    int id :
                    clusterIds
            ) {
                clusterPieces.add(
                        pieces.get(id)
                );
            }

            Collections.sort(
                    clusterPieces,
                    (a, b) -> {

                        float dy =
                                a.box.t -
                                        b.box.t;

                        if (
                                Math.abs(dy) >
                                        .025f
                        ) {

                            return dy < 0
                                    ? -1
                                    : 1;
                        }

                        return Float.compare(
                                a.box.l,
                                b.box.l
                        );
                    }
            );

            StringBuilder joinedText =
                    new StringBuilder();

            Box finalBox = null;

            for (
                    Piece piece :
                    clusterPieces
            ) {

                if (
                        joinedText.length() >
                                0
                ) {
                    joinedText.append(
                            ' '
                    );
                }

                joinedText.append(
                        piece.text
                );

                finalBox =
                        finalBox == null
                                ? piece.box
                                : union(
                                finalBox,
                                piece.box
                        );
            }

            String combined =
                    cleanText(
                            joinedText.toString()
                    );

            boolean resolved =
                    false;

            try {

                resolved =
                        resolver
                                .resolve(
                                        combined
                                )
                                .medicine != null;

            } catch (RuntimeException ignored) {
                /*
                 * Bad OCR must never crash the camera loop.
                 */
            }

            boolean medicineLooking =
                    looksMedicineLike(
                            combined
                    );

            /*
             * Only preserve OCR that is either:
             *
             * 1. actually resolvable by MediLens, OR
             * 2. visually/textually resembles a medicine label.
             *
             * Normal environmental text is discarded here.
             */
            if (
                    resolved ||
                            medicineLooking
            ) {

                for (
                        int id :
                        clusterIds
                ) {
                    consumed[id] =
                            true;
                }

                String key =
                        canonical(
                                combined
                        );

                result.add(
                        new OcrCandidate(
                                expand(
                                        finalBox,
                                        .012f
                                ),
                                combined,
                                key,
                                resolved,
                                textQuality(
                                        combined,
                                        resolved
                                )
                        )
                );
            }
        }

        /*
         * Sometimes ML Kit already returns an excellent single line such as:
         *
         * DOLO 650 PARACETAMOL 650 MG
         *
         * Keep high-quality unconsumed single lines as well.
         */
        for (
                int i = 0;
                i < pieces.size();
                i++
        ) {

            if (
                    consumed[i]
            ) {
                continue;
            }

            Piece piece =
                    pieces.get(i);

            boolean resolved =
                    false;

            try {

                resolved =
                        resolver
                                .resolve(
                                        piece.text
                                )
                                .medicine != null;

            } catch (RuntimeException ignored) {
            }

            if (
                    !resolved &&
                            !looksMedicineLike(
                                    piece.text
                            )
            ) {
                continue;
            }

            result.add(
                    new OcrCandidate(
                            expand(
                                    piece.box,
                                    .010f
                            ),
                            piece.text,
                            canonical(
                                    piece.text
                            ),
                            resolved,
                            textQuality(
                                    piece.text,
                                    resolved
                            )
                    )
            );
        }

        /*
         * Highest-quality/resolved candidates first.
         */
        result.sort(
                (a, b) -> {

                    if (
                            a.resolved !=
                                    b.resolved
                    ) {

                        return a.resolved
                                ? -1
                                : 1;
                    }

                    return Float.compare(
                            b.quality,
                            a.quality
                    );
                }
        );

        /*
         * Remove overlapping alternatives.
         */
        List<OcrCandidate> deduped =
                new ArrayList<>();

        for (
                OcrCandidate candidate :
                result
        ) {

            boolean overlaps =
                    false;

            for (
                    OcrCandidate existing :
                    deduped
            ) {

                if (
                        iou(
                                candidate.box,
                                existing.box
                        ) > .45f
                ) {

                    overlaps = true;

                    break;
                }
            }

            if (!overlaps) {

                deduped.add(
                        candidate
                );
            }

            if (
                    deduped.size() >= 8
            ) {
                break;
            }
        }

        return deduped;
    }


    /**
     * Temporal OCR stabilizer.
     *
     * ML Kit text can change between:
     *
     * "DOLO 650"
     * "D0L0 650"
     * "DOLO-65O"
     *
     * even while the phone barely moves.
     *
     * These should be treated as one physical candidate, not three new labels.
     */
    private List<Observation> stabilizeCandidates(
            List<OcrCandidate> incoming,
            long now
    ) {

        synchronized (
                temporalLock
        ) {

            Set<StableCandidate> matched =
                    new HashSet<>();

            for (
                    OcrCandidate candidate :
                    incoming
            ) {

                StableCandidate best = null;

                float bestScore =
                        -1f;

                for (
                        StableCandidate existing :
                        stableCandidates
                ) {

                    if (
                            matched.contains(
                                    existing
                            )
                    ) {
                        continue;
                    }

                    float overlap =
                            iou(
                                    existing.box,
                                    candidate.box
                            );

                    float dx =
                            existing.box.cx() -
                                    candidate.box.cx();

                    float dy =
                            existing.box.cy() -
                                    candidate.box.cy();

                    float distance =
                            (float) Math.sqrt(
                                    dx * dx +
                                            dy * dy
                            );

                    float similarity =
                            textSimilarity(
                                    existing.key,
                                    candidate.key
                            );

                    boolean samePhysicalRegion =
                            overlap > .20f ||
                                    distance < .065f;

                    if (
                            !samePhysicalRegion
                    ) {
                        continue;
                    }

                    /*
                     * Either the OCR strings must resemble each other, or the
                     * boxes must very strongly overlap.
                     */
                    if (
                            similarity < .38f &&
                                    overlap < .48f
                    ) {
                        continue;
                    }

                    float score =
                            overlap * .55f +
                                    similarity * .35f +
                                    Math.max(
                                            0f,
                                            .10f -
                                                    distance
                                    );

                    if (
                            score >
                                    bestScore
                    ) {

                        bestScore =
                                score;

                        best =
                                existing;
                    }
                }

                if (
                        best == null
                ) {

                    StableCandidate created =
                            new StableCandidate(
                                    candidate,
                                    now
                            );

                    stableCandidates.add(
                            created
                    );

                    matched.add(
                            created
                    );

                    continue;
                }

                matched.add(
                        best
                );

                best.hits =
                        Math.min(
                                30,
                                best.hits + 1
                        );

                best.lastSeen =
                        now;

                /*
                 * Smooth the OCR box so the overlay doesn't jitter.
                 */
                best.box =
                        smoothBox(
                                best.box,
                                candidate.box,
                                .32f
                        );

                /*
                 * Prefer an actually resolved reading over an unresolved one.
                 * Otherwise preserve the highest-quality OCR string we've seen.
                 */
                if (
                        (
                                candidate.resolved &&
                                        !best.resolved
                        ) ||
                                candidate.quality >
                                        best.quality +
                                                .08f
                ) {

                    best.text =
                            candidate.text;

                    best.key =
                            candidate.key;

                    best.quality =
                            candidate.quality;
                }

                if (
                        candidate.resolved
                ) {
                    best.resolved =
                            true;
                }
            }

            /*
             * Remove very old candidates.
             */
            Iterator<StableCandidate> iterator =
                    stableCandidates.iterator();

            while (
                    iterator.hasNext()
            ) {

                StableCandidate candidate =
                        iterator.next();

                if (
                        now -
                                candidate.lastSeen >
                                OCR_FORGET_MS
                ) {

                    iterator.remove();
                }
            }

            List<StableCandidate> displayable =
                    new ArrayList<>();

            for (
                    StableCandidate candidate :
                    stableCandidates
            ) {

                /*
                 * Resolved candidates still require stability.
                 *
                 * We want to avoid one single accidental frame resolving into
                 * an incorrect medicine.
                 */
                if (
                        candidate.hits <
                                REQUIRED_STABLE_HITS
                ) {
                    continue;
                }

                if (
                        now -
                                candidate.lastSeen >
                                OCR_MEMORY_MS
                ) {
                    continue;
                }

                displayable.add(
                        candidate
                );
            }

            displayable.sort(
                    (a, b) -> {

                        if (
                                a.resolved !=
                                        b.resolved
                        ) {

                            return a.resolved
                                    ? -1
                                    : 1;
                        }

                        int hitCompare =
                                Integer.compare(
                                        b.hits,
                                        a.hits
                                );

                        if (
                                hitCompare != 0
                        ) {
                            return hitCompare;
                        }

                        return Float.compare(
                                b.quality,
                                a.quality
                        );
                    }
            );

            List<Observation> output =
                    new ArrayList<>();

            for (
                    StableCandidate candidate :
                    displayable
            ) {

                output.add(
                        new Observation(
                                candidate.box,
                                candidate.text,
                                now
                        )
                );

                if (
                        output.size() >=
                                MAX_STABLE_OBSERVATIONS
                ) {
                    break;
                }
            }

            return output;
        }
    }


    private static boolean nearby(
            Box a,
            Box b
    ) {

        float horizontalGap =
                Math.max(
                        0,
                        Math.max(
                                a.l,
                                b.l
                        ) -
                                Math.min(
                                        a.r,
                                        b.r
                                )
                );

        float verticalGap =
                Math.max(
                        0,
                        Math.max(
                                a.t,
                                b.t
                        ) -
                                Math.min(
                                        a.b,
                                        b.b
                                )
                );

        float dx =
                Math.abs(
                        a.cx() -
                                b.cx()
                );

        float dy =
                Math.abs(
                        a.cy() -
                                b.cy()
                );

        /*
         * Prefer vertically stacked lines, which is how medicine packages are
         * generally printed.
         */
        return (
                horizontalGap <
                        .055f &&
                        verticalGap <
                                .085f
        ) ||
                (
                        dx <
                                .18f &&
                                dy <
                                        .11f
                );
    }


    private static String cleanText(
            String value
    ) {

        if (
                value == null
        ) {
            return "";
        }

        String normalized =
                Normalizer.normalize(
                        value,
                        Normalizer.Form.NFKC
                );

        normalized =
                normalized
                        .replace('\n', ' ')
                        .replace('\r', ' ')
                        .replace('\t', ' ');

        /*
         * Remove unusual OCR garbage while preserving characters commonly seen
         * on medicine packaging.
         */
        normalized =
                normalized.replaceAll(
                        "[^\\p{L}\\p{N}%µ+./()\\-,:&' ]",
                        " "
                );

        normalized =
                normalized.replaceAll(
                        "\\s+",
                        " "
                );

        return normalized.trim();
    }


    private static boolean isSaneText(
            String text
    ) {

        if (
                text == null
        ) {
            return false;
        }

        int length =
                text.length();

        if (
                length < 3 ||
                        length > 120
        ) {
            return false;
        }

        int alphaNumeric = 0;
        int letters = 0;
        int digits = 0;
        int weird = 0;

        char previous =
                0;

        int repeated =
                1;

        int worstRepeat =
                1;

        for (
                int i = 0;
                i < length;
                i++
        ) {

            char c =
                    text.charAt(i);

            if (
                    Character.isLetterOrDigit(
                            c
                    )
            ) {

                alphaNumeric++;

                if (
                        Character.isLetter(
                                c
                        )
                ) {
                    letters++;
                }

                if (
                        Character.isDigit(
                                c
                        )
                ) {
                    digits++;
                }

            } else if (
                    !Character.isWhitespace(
                            c
                    ) &&
                            "%µ+./()\\-,:&'"
                                    .indexOf(c) < 0
            ) {

                weird++;
            }

            if (
                    i > 0 &&
                            Character.toUpperCase(c) ==
                                    Character.toUpperCase(
                                            previous
                                    )
            ) {

                repeated++;

                worstRepeat =
                        Math.max(
                                worstRepeat,
                                repeated
                        );

            } else {

                repeated = 1;
            }

            previous = c;
        }

        if (
                alphaNumeric < 3
        ) {
            return false;
        }

        float ratio =
                alphaNumeric /
                        (float) length;

        if (
                ratio < .50f
        ) {
            return false;
        }

        /*
         * "|||||||", "IIIIIIII", "00000000", etc.
         */
        if (
                worstRepeat >= 7
        ) {
            return false;
        }

        if (
                weird >
                        Math.max(
                                2,
                                length / 8
                        )
        ) {
            return false;
        }

        /*
         * Pure number detections such as prices or timestamps are useless.
         */
        if (
                letters == 0
        ) {
            return false;
        }

        /*
         * Single-letter noise mixed with numbers is generally OCR garbage.
         */
        return !(
                letters <= 1 &&
                        digits >= 2
        );
    }


    private static boolean looksMedicineLike(
            String text
    ) {

        if (
                text == null ||
                        text.length() <
                                5
        ) {
            return false;
        }

        String upper =
                text.toUpperCase(
                        Locale.US
                );

        boolean hasDose =
                DOSE_PATTERN
                        .matcher(upper)
                        .find();

        boolean hasDosageForm =
                DOSAGE_FORM_PATTERN
                        .matcher(upper)
                        .find();

        int letters = 0;
        int words = 0;

        boolean insideWord =
                false;

        for (
                int i = 0;
                i < upper.length();
                i++
        ) {

            char c =
                    upper.charAt(i);

            if (
                    Character.isLetter(
                            c
                    )
            ) {
                letters++;
            }

            if (
                    Character.isLetterOrDigit(
                            c
                    )
            ) {

                if (
                        !insideWord
                ) {

                    words++;

                    insideWord = true;
                }

            } else {

                insideWord = false;
            }
        }

        /*
         * "650 MG" alone is not enough.
         *
         * "DOLO 650 MG" is.
         */
        if (
                hasDose &&
                        letters >= 4 &&
                        words >= 2
        ) {
            return true;
        }

        /*
         * Formulation language such as "Paracetamol Tablets IP".
         */
        return hasDosageForm &&
                letters >= 7 &&
                words >= 2;
    }


    private static float textQuality(
            String text,
            boolean resolved
    ) {

        if (
                text == null ||
                        text.isEmpty()
        ) {
            return 0f;
        }

        int alphaNumeric = 0;

        int length =
                text.length();

        for (
                int i = 0;
                i < length;
                i++
        ) {

            if (
                    Character.isLetterOrDigit(
                            text.charAt(i)
                    )
            ) {
                alphaNumeric++;
            }
        }

        float score =
                alphaNumeric /
                        (float) Math.max(
                                1,
                                length
                        );

        if (
                DOSE_PATTERN
                        .matcher(text)
                        .find()
        ) {
            score += .25f;
        }

        if (
                DOSAGE_FORM_PATTERN
                        .matcher(text)
                        .find()
        ) {
            score += .15f;
        }

        if (
                resolved
        ) {
            score += 1f;
        }

        /*
         * Very long OCR strings are often multiple unrelated package regions.
         */
        if (
                length > 90
        ) {
            score -= .15f;
        }

        return score;
    }


    private static String canonical(
            String text
    ) {

        if (
                text == null
        ) {
            return "";
        }

        String value =
                Normalizer.normalize(
                                text,
                                Normalizer.Form.NFKC
                        )
                        .toUpperCase(
                                Locale.US
                        )
                        .replaceAll(
                                "[^A-Z0-9]+",
                                " "
                        )
                        .trim()
                        .replaceAll(
                                "\\s+",
                                " "
                        );

        return value;
    }


    private static float textSimilarity(
            String a,
            String b
    ) {

        if (
                a == null ||
                        b == null
        ) {
            return 0f;
        }

        if (
                a.equals(b)
        ) {
            return 1f;
        }

        if (
                a.isEmpty() ||
                        b.isEmpty()
        ) {
            return 0f;
        }

        Set<String> left =
                tokenSet(a);

        Set<String> right =
                tokenSet(b);

        int intersection = 0;

        for (
                String token :
                left
        ) {

            if (
                    right.contains(
                            token
                    )
            ) {
                intersection++;
            }
        }

        int union =
                left.size() +
                        right.size() -
                        intersection;

        float tokenScore =
                union == 0
                        ? 0f
                        : intersection /
                          (float) union;

        String compactA =
                a.replace(
                        " ",
                        ""
                );

        String compactB =
                b.replace(
                        " ",
                        ""
                );

        int prefix = 0;

        int maxPrefix =
                Math.min(
                        compactA.length(),
                        compactB.length()
                );

        while (
                prefix <
                        maxPrefix &&
                        compactA.charAt(prefix) ==
                                compactB.charAt(prefix)
        ) {

            prefix++;
        }

        float prefixScore =
                prefix /
                        (float) Math.max(
                                1,
                                Math.max(
                                        compactA.length(),
                                        compactB.length()
                                )
                        );

        /*
         * Token overlap matters most, but a shared prefix helps with OCR errors
         * such as DOLO versus D0LO.
         */
        return Math.max(
                tokenScore,
                tokenScore * .75f +
                        prefixScore * .25f
        );
    }


    private static Set<String> tokenSet(
            String text
    ) {

        Set<String> result =
                new HashSet<>();

        String[] tokens =
                text.split(
                        " "
                );

        for (
                String token :
                tokens
        ) {

            if (
                    token.length() >= 2
            ) {
                result.add(
                        token
                );
            }
        }

        return result;
    }


    private static Box mapRect(
            Rect rect,
            int w,
            int h,
            int rotation,
            float[] affine
    ) {

        float left = 1;
        float top = 1;
        float right = 0;
        float bottom = 0;

        float[][] corners = {

                {
                        rect.left,
                        rect.top
                },

                {
                        rect.right,
                        rect.top
                },

                {
                        rect.left,
                        rect.bottom
                },

                {
                        rect.right,
                        rect.bottom
                }
        };

        for (
                float[] corner :
                corners
        ) {

            float[] raw =
                    Coordinates.unrotate(
                            corner[0],
                            corner[1],
                            w,
                            h,
                            rotation
                    );

            float[] point =
                    Coordinates.view(
                            raw[0],
                            raw[1],
                            w,
                            h,
                            affine
                    );

            left =
                    Math.min(
                            left,
                            point[0]
                    );

            top =
                    Math.min(
                            top,
                            point[1]
                    );

            right =
                    Math.max(
                            right,
                            point[0]
                    );

            bottom =
                    Math.max(
                            bottom,
                            point[1]
                    );
        }

        left =
                Math.max(
                        0,
                        left
                );

        top =
                Math.max(
                        0,
                        top
                );

        right =
                Math.min(
                        1,
                        right
                );

        bottom =
                Math.min(
                        1,
                        bottom
                );

        if (
                right - left <
                        .008f ||
                        bottom - top <
                                .005f
        ) {
            return null;
        }

        return new Box(
                left,
                top,
                right,
                bottom
        );
    }


    private static Box union(
            Box a,
            Box b
    ) {

        return new Box(
                Math.min(
                        a.l,
                        b.l
                ),
                Math.min(
                        a.t,
                        b.t
                ),
                Math.max(
                        a.r,
                        b.r
                ),
                Math.max(
                        a.b,
                        b.b
                )
        );
    }


    private static Box expand(
            Box box,
            float padding
    ) {

        float left =
                Math.max(
                        0,
                        box.l -
                                padding
                );

        float top =
                Math.max(
                        0,
                        box.t -
                                padding
                );

        float right =
                Math.min(
                        1,
                        box.r +
                                padding
                );

        float bottom =
                Math.min(
                        1,
                        box.b +
                                padding
                );

        return new Box(
                left,
                top,
                right,
                bottom
        );
    }


    private static Box smoothBox(
            Box oldBox,
            Box newBox,
            float amount
    ) {

        float inverse =
                1f -
                        amount;

        return new Box(
                oldBox.l *
                        inverse +
                        newBox.l *
                                amount,

                oldBox.t *
                        inverse +
                        newBox.t *
                                amount,

                oldBox.r *
                        inverse +
                        newBox.r *
                                amount,

                oldBox.b *
                        inverse +
                        newBox.b *
                                amount
        );
    }


    private static float iou(
            Box a,
            Box b
    ) {

        float left =
                Math.max(
                        a.l,
                        b.l
                );

        float top =
                Math.max(
                        a.t,
                        b.t
                );

        float right =
                Math.min(
                        a.r,
                        b.r
                );

        float bottom =
                Math.min(
                        a.b,
                        b.b
                );

        float width =
                Math.max(
                        0,
                        right -
                                left
                );

        float height =
                Math.max(
                        0,
                        bottom -
                                top
                );

        float intersection =
                width *
                        height;

        if (
                intersection <=
                        0
        ) {
            return 0f;
        }

        float union =
                a.area() +
                        b.area() -
                        intersection;

        if (
                union <=
                        0
        ) {
            return 0f;
        }

        return intersection /
                union;
    }
}
