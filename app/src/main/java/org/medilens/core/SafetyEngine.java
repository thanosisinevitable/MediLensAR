package org.medilens.core;
import java.util.*;
import static org.medilens.core.Domain.*;
public final class SafetyEngine {
    private final Map<String,Interaction> interactions=new HashMap<>();
    public static String pair(String a,String b){return a.compareTo(b)<0?a+"|"+b:b+"|"+a;}
    public SafetyEngine(List<Interaction> rows){for(Interaction i:rows)interactions.put(pair(i.left,i.right),i);}
    public List<Alert> evaluate(List<Track> tracks){
        Map<String,Alert> out=new TreeMap<>();
        for(int a=0;a<tracks.size();a++)for(int b=a+1;b<tracks.size();b++){
            Track l=tracks.get(a),r=tracks.get(b);
            if(l.match.medicine==null||r.match.medicine==null)continue;
            long lo=Math.min(l.id,r.id),hi=Math.max(l.id,r.id);String prefix=lo+":"+hi+":";
            for(Ingredient x:l.match.medicine.ingredients)for(Ingredient y:r.match.medicine.ingredients){
                if(x.id.equals(y.id)){String key=prefix+"duplicate:"+x.id;out.put(key,new Alert(key,"Shared active ingredient",x.name+" appears in both identified label regions. Confirm these are separate packages; scanning does not establish what you take.","DUPLICATE",l.match.medicine.sourceId,lo,hi,2));continue;}
                Interaction i=interactions.get(pair(x.id,y.id));
                if(i!=null){String key=prefix+i.id;out.put(key,new Alert(key,"Known interaction",i.explanation,i.severity,i.sourceId,lo,hi,0));}
                else {String key=prefix+"unknown:"+pair(x.id,y.id);out.put(key,new Alert(key,"Interaction information unavailable","No local evidence for "+x.name+" and "+y.name+". This does not establish safety.","NO DATA","",lo,hi,3));}
            }
        }
        List<Alert> result=new ArrayList<>(out.values());result.sort(Comparator.comparingInt(a->a.priority));return result;
    }
    public static final class AlertManager {
        private final Map<String,Long> since=new HashMap<>();
        public List<Alert> update(List<Alert> input,long now){
            Set<String> present=new HashSet<>();List<Alert> out=new ArrayList<>();
            for(Alert a:input){if(!present.add(a.key))continue;since.putIfAbsent(a.key,now);if(a.priority==0 || now-since.get(a.key)>=Config.ALERT_DEBOUNCE_MS)out.add(a);}
            since.keySet().retainAll(present);out.sort(Comparator.comparingInt(a->a.priority));return out;
        }
        public void clear(){since.clear();}
    }
}
