package org.medilens.core;

import java.util.*;
import static org.medilens.core.Domain.*;

/**
 * Stable one-to-one OCR track association.
 *
 * Goals:
 *  - resist OCR box jitter
 *  - avoid identity jumping between nearby labels
 *  - keep a confirmed medicine visually stable through short OCR misses
 *  - do not immediately replace a confirmed medicine because of one bad OCR frame
 *  - require consecutive agreement before confirming or switching identity
 */
public final class Tracker {

    private static final long VISUAL_HOLD_MS = 1800L;
    private static final long RAW_HOLD_MS = 900L;
    private static final float MAX_CENTER_DISTANCE = 0.115f;
    private static final float MIN_ASSOCIATION_SCORE = 0.18f;
    private static final float AMBIGUITY_MARGIN = 0.055f;
    private static final float BOX_SMOOTHING = 0.34f;

    private final List<Track> tracks = new ArrayList<>();
    private long next = 1;
    private long last = -1;

    private final Resolver resolver;

    public Tracker(Resolver resolver) {
        this.resolver = resolver;
    }

    public void clear() {
        tracks.clear();
        last = -1;
    }

    public List<Track> update(List<Observation> observations, long now) {
        if (now <= last) {
            return current(now);
        }

        last = now;
        tracks.removeIf(t -> isExpired(t, now));

        Set<Long> used = new HashSet<>();

        List<Observation> ordered = new ArrayList<>(observations);
        ordered.sort((a, b) -> Float.compare(b.box.area(), a.box.area()));

        for (Observation observation : ordered) {
            if (now - observation.time > Config.MAX_RESULT_AGE_MS || observation.time > now) {
                continue;
            }

            Association association = findBestTrack(observation, used);
            Track track = association.track;

            // Never transfer identity when two nearby tracks are equally plausible.
            if (association.ambiguous) {
                continue;
            }

            if (track == null) {
                if (tracks.size() >= Config.MAX_TRACKS) {
                    continue;
                }

                track = new Track(next++, observation);
                tracks.add(track);
            }

            used.add(track.id);

            long previousSeen = track.seen;
            Phase previousPhase = track.phase;
            Match previousMatch = track.match;
            Medicine previousMedicine = previousMatch == null ? null : previousMatch.medicine;

            String previousConfirmedId = previousMedicine == null ? "" : previousMedicine.id;
            String previousPendingCandidate = track.candidate == null ? "" : track.candidate;
            int previousHits = track.hits;

            Match incomingMatch = resolver.resolve(observation.text);
            String incomingCandidate = incomingMatch.medicine == null ? "" : incomingMatch.medicine.id;

            boolean continuous =
                    previousSeen > 0 &&
                            now - previousSeen <= Math.max(Config.LABEL_FRESH_MS, VISUAL_HOLD_MS);

            // Smooth overlay movement instead of replacing the OCR box every frame.
            track.box = smooth(track.box, observation.box, BOX_SMOOTHING);
            track.seen = observation.time;

            // Do not visibly mutate a confirmed medicine card into garbage OCR.
            if (incomingMatch.medicine != null || previousMedicine == null) {
                track.text = observation.text;
            }

            /*
             * OCR failed to resolve on this pass.
             * Preserve a previously confirmed medicine through short OCR misses.
             */
            if (incomingCandidate.isEmpty()) {
                if (previousMedicine != null &&
                        continuous &&
                        (previousPhase == Phase.RESOLVED || previousPhase == Phase.STABILIZING)) {

                    track.match = previousMatch;
                    track.provisional = previousMedicine;
                    track.candidate = previousConfirmedId;
                    track.hits = Math.max(previousHits, Math.max(1, Config.CONFIRMATIONS - 1));
                    track.phase = Phase.STABILIZING;
                } else {
                    track.provisional = null;
                    track.match = incomingMatch;
                    track.hits = 0;
                    track.candidate = "";
                    track.phase = Phase.UNRESOLVED;
                }

                continue;
            }

            /* Incoming OCR agrees with the currently confirmed medicine. */
            if (!previousConfirmedId.isEmpty() && incomingCandidate.equals(previousConfirmedId)) {
                int hits = continuous
                        ? Math.max(previousHits + 1, Config.CONFIRMATIONS)
                        : 1;

                track.hits = hits;
                track.candidate = incomingCandidate;
                track.provisional = incomingMatch.medicine;

                if (hits >= Config.CONFIRMATIONS) {
                    track.match = new Match(
                            incomingMatch.medicine,
                            Confidence.HIGH,
                            "Brand, ingredient and strength matched in " +
                                    hits +
                                    " consecutive observations"
                    );
                    track.phase = Phase.RESOLVED;
                } else {
                    track.match = incomingMatch;
                    track.phase = Phase.STABILIZING;
                }

                continue;
            }

            /*
             * A confirmed track suddenly appears to be a different medicine.
             * Do not switch identity from one noisy OCR result. Require repeated
             * agreement before changing the confirmed medicine.
             */
            if (previousMedicine != null &&
                    !previousConfirmedId.isEmpty() &&
                    !incomingCandidate.equals(previousConfirmedId)) {

                int switchHits =
                        continuous && incomingCandidate.equals(previousPendingCandidate)
                                ? previousHits + 1
                                : 1;

                track.candidate = incomingCandidate;
                track.hits = switchHits;

                if (switchHits >= Config.CONFIRMATIONS) {
                    track.provisional = incomingMatch.medicine;
                    track.match = new Match(
                            incomingMatch.medicine,
                            Confidence.HIGH,
                            "Label identity changed after " +
                                    switchHits +
                                    " consistent observations"
                    );
                    track.phase = Phase.RESOLVED;
                } else {
                    track.provisional = previousMedicine;
                    track.match = previousMatch;
                    track.phase = Phase.STABILIZING;
                }

                continue;
            }

            /* New/unconfirmed track: require repeated agreement. */
            int hits =
                    continuous && incomingCandidate.equals(previousPendingCandidate)
                            ? previousHits + 1
                            : 1;

            track.hits = hits;
            track.candidate = incomingCandidate;
            track.provisional = incomingMatch.medicine;

            if (hits >= Config.CONFIRMATIONS) {
                track.match = new Match(
                        incomingMatch.medicine,
                        Confidence.HIGH,
                        "Brand, ingredient and strength matched in " +
                                hits +
                                " consecutive observations"
                );
                track.phase = Phase.RESOLVED;
            } else {
                track.match = Match.unresolved(
                        "Confirming label " +
                                hits +
                                "/" +
                                Config.CONFIRMATIONS
                );
                track.phase = Phase.STABILIZING;
            }
        }

        /*
         * Missing for one OCR pass no longer means LOST immediately.
         * This is the main anti-flicker behavior.
         */
        for (Track track : tracks) {
            if (used.contains(track.id)) {
                continue;
            }

            long age = now - track.seen;

            if (track.phase == Phase.RESOLVED) {
                if (age <= VISUAL_HOLD_MS) {
                    track.phase = Phase.STABILIZING;
                    track.hits = Math.max(
                            track.hits,
                            Math.max(1, Config.CONFIRMATIONS - 1)
                    );
                } else {
                    markLost(track);
                }
            } else if (track.phase == Phase.STABILIZING) {
                if (age > VISUAL_HOLD_MS) {
                    markLost(track);
                }
            } else if (age > RAW_HOLD_MS) {
                markLost(track);
            }
        }

        return current(now);
    }

    public List<Track> current(long now) {
        List<Track> out = new ArrayList<>();

        for (Track track : tracks) {
            long age = now - track.seen;

            if (track.phase == Phase.LOST) {
                continue;
            }

            long allowedAge =
                    (track.phase == Phase.RESOLVED || track.phase == Phase.STABILIZING)
                            ? Math.max(Config.LABEL_FRESH_MS, VISUAL_HOLD_MS)
                            : Math.min(
                            Math.max(Config.LABEL_FRESH_MS, RAW_HOLD_MS),
                            VISUAL_HOLD_MS
                    );

            if (age <= allowedAge) {
                out.add(track);
            }
        }

        return out;
    }

    private Association findBestTrack(Observation observation, Set<Long> used) {
        Track best = null;
        float bestScore = MIN_ASSOCIATION_SCORE;
        float secondScore = -1f;

        for (Track track : tracks) {
            if (used.contains(track.id) || track.phase == Phase.LOST) {
                continue;
            }

            float overlap = track.box.iou(observation.box);
            float distance = centerDistance(track.box, observation.box);

            if (overlap < Config.MIN_IOU && distance > MAX_CENTER_DISTANCE) {
                continue;
            }

            float proximity = Math.max(0f, 1f - (distance / MAX_CENTER_DISTANCE));
            float score = overlap * 0.72f + proximity * 0.28f;

            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = track;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }

        boolean ambiguous =
                best != null &&
                        secondScore >= MIN_ASSOCIATION_SCORE &&
                        bestScore - secondScore < AMBIGUITY_MARGIN;

        return new Association(best, ambiguous);
    }

    private boolean isExpired(Track track, long now) {
        long age = now - track.seen;

        if (track.phase == Phase.RESOLVED || track.phase == Phase.STABILIZING) {
            return age > Math.max(Config.TRACK_TTL_MS, VISUAL_HOLD_MS);
        }

        return age > Math.max(Config.TRACK_TTL_MS, RAW_HOLD_MS);
    }

    private static void markLost(Track track) {
        track.provisional = null;
        track.match = Match.unresolved("Label lost; reacquiring");
        track.hits = 0;
        track.candidate = "";
        track.phase = Phase.LOST;
    }

    private static float centerDistance(Box a, Box b) {
        float dx = a.cx() - b.cx();
        float dy = a.cy() - b.cy();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static Box smooth(Box oldBox, Box newBox, float amount) {
        if (oldBox == null) {
            return newBox;
        }

        float inverse = 1f - amount;

        return new Box(
                oldBox.l * inverse + newBox.l * amount,
                oldBox.t * inverse + newBox.t * amount,
                oldBox.r * inverse + newBox.r * amount,
                oldBox.b * inverse + newBox.b * amount
        );
    }

    private static final class Association {
        final Track track;
        final boolean ambiguous;

        Association(Track track, boolean ambiguous) {
            this.track = track;
            this.ambiguous = ambiguous;
        }
    }
}
