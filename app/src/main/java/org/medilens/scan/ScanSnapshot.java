package org.medilens.scan;
import java.util.*;
import org.medilens.core.Domain.*;
/** Immutable UI hand-off: no mutable track or ARCore Frame crosses threads. */
public final class ScanSnapshot {
    public static final class Label {
        public final long id;public final Box box;public final float x,y;public final boolean anchored;
        public final Match match;public final Phase phase;public final Medicine provisional;public final String text;
        public Label(long id,Box box,float x,float y,boolean anchored,Match match,Phase phase,Medicine provisional,String text){this.id=id;this.box=box;this.x=x;this.y=y;this.anchored=anchored;this.match=match;this.phase=phase;this.provisional=provisional;this.text=text==null?"":text;}
    }
    public final List<Label> labels;public final List<Alert> alerts;public final String status;public final long ocrMs;
    public ScanSnapshot(List<Label> l,List<Alert> a,String status,long ocrMs){labels=Collections.unmodifiableList(new ArrayList<>(l));alerts=Collections.unmodifiableList(new ArrayList<>(a));this.status=status;this.ocrMs=ocrMs;}
    public static ScanSnapshot empty(String status){return new ScanSnapshot(Collections.emptyList(),Collections.emptyList(),status,0);}
}
