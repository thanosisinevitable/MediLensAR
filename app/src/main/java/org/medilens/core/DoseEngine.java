package org.medilens.core;
import java.time.*;
import java.util.*;
import static org.medilens.core.Domain.*;
/** A user-entered occurrence, never a generated clinical schedule. */
public final class DoseEngine {
    private DoseEngine() {}
    public static DoseState state(Instant now,Instant due,DoseState recorded){
        if(due==null)return DoseState.UNKNOWN;
        if(recorded==DoseState.TAKEN||recorded==DoseState.SKIPPED)return recorded;
        if(now.isBefore(due.minusSeconds(Config.DOSE_EARLY_SECONDS)))return DoseState.UPCOMING;
        if(now.isAfter(due.plusSeconds(Config.DOSE_LATE_SECONDS)))return DoseState.OVERDUE;
        return DoseState.DUE;
    }
    public static Instant localOccurrence(LocalDate date,LocalTime time,ZoneId zone){
        LocalDateTime local=LocalDateTime.of(date,time);List<ZoneOffset> offsets=zone.getRules().getValidOffsets(local);
        if(offsets.size()!=1)throw new IllegalArgumentException("This local time is skipped or repeated by daylight saving. Choose an unambiguous time.");
        return local.toInstant(offsets.get(0));
    }
}
