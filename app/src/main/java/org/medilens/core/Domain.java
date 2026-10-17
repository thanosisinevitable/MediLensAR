package org.medilens.core;
import java.util.*;
public final class Domain {
    private Domain() {}
    public enum Confidence { HIGH, MEDIUM, LOW, UNRESOLVED }
    public enum DoseState { UPCOMING, DUE, TAKEN, OVERDUE, SKIPPED, UNKNOWN }
    public enum Phase { NEW, STABILIZING, RESOLVED, UNRESOLVED, LOST }
    public static final class Box {
        public final float l,t,r,b;
        public Box(float l,float t,float r,float b) {
            if (!Float.isFinite(l+t+r+b) || r<=l || b<=t) throw new IllegalArgumentException("Invalid box");
            this.l=l;this.t=t;this.r=r;this.b=b;
        }
        public float cx(){return (l+r)/2;} public float cy(){return (t+b)/2;}
        public float area(){return (r-l)*(b-t);}
        public float iou(Box x) {float a=Math.max(0,Math.min(r,x.r)-Math.max(l,x.l))*Math.max(0,Math.min(b,x.b)-Math.max(t,x.t));return a/(area()+x.area()-a);}
    }
    public static final class Ingredient {
        public final String id,name,strength,unit;
        public Ingredient(String id,String name,String strength,String unit){this.id=id;this.name=name;this.strength=strength;this.unit=unit;}
        public String display(){return name+" "+strength+" "+unit;}
    }
    public static final class Medicine {
        public final String id,name,form,sourceId;
        public final List<String> aliases,requiredTokens,excludedTokens;
        public final List<Ingredient> ingredients;
        public Medicine(String id,String name,String form,String sourceId,List<String> aliases,List<String> required,List<String> excluded,List<Ingredient> ingredients) {
            this.id=id;this.name=name;this.form=form;this.sourceId=sourceId;
            this.aliases=Collections.unmodifiableList(new ArrayList<>(aliases));this.requiredTokens=Collections.unmodifiableList(new ArrayList<>(required));this.excludedTokens=Collections.unmodifiableList(new ArrayList<>(excluded));this.ingredients=Collections.unmodifiableList(new ArrayList<>(ingredients));
        }
        public String composition(){StringJoiner j=new StringJoiner(" + ");for(Ingredient i:ingredients)j.add(i.display());return j.toString();}
    }
    public static final class Match {
        public final Medicine medicine; public final Confidence confidence; public final String reason;
        public Match(Medicine m,Confidence c,String r){medicine=m;confidence=c;reason=r;}
        public static Match unresolved(String r){return new Match(null,Confidence.UNRESOLVED,r);}
    }
    public static final class Observation {
        public final Box box; public final String text; public final long time;
        public Observation(Box b,String t,long at){box=b;text=t;time=at;}
    }
    public static final class Track {
        public final long id;
        public Box box; public long seen; public int hits=0; public String candidate=""; public String text="";
        public Match match=Match.unresolved("Hold the label steady");public Medicine provisional=null;public Phase phase=Phase.NEW;
        public Track(long id,Observation o){this.id=id;box=o.box;seen=o.time;text=o.text;}
    }
    public static final class Interaction {
        public final String id,left,right,severity,explanation,sourceId;
        public Interaction(String id,String left,String right,String severity,String explanation,String sourceId){this.id=id;this.left=left;this.right=right;this.severity=severity;this.explanation=explanation;this.sourceId=sourceId;}
    }
    public static final class Alert {
        public final String key,title,detail,severity,sourceId;public final long left,right;public final int priority;
        public Alert(String key,String title,String detail,String severity,String source,long l,long r,int p){this.key=key;this.title=title;this.detail=detail;this.severity=severity;sourceId=source;left=l;right=r;priority=p;}
    }
}
