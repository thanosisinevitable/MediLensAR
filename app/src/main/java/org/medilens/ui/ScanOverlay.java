package org.medilens.ui;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.os.SystemClock;
import java.util.*;
import org.medilens.scan.ScanSnapshot;
import org.medilens.core.Domain.*;
/** Camera-aligned annotation layer with persistent OCR previews and medicine cards. */
public final class ScanOverlay extends View {
    public interface Selection {void select(ScanSnapshot.Label label);}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final float d;
    private ScanSnapshot snapshot=ScanSnapshot.empty("Starting camera");private final Selection selection;
    private final Map<Long,RectF> targets=new HashMap<>();private final Map<String,String> doseLabels=new HashMap<>();
    private static final long DETECTION_HOLD_MS=1800;
    private static final long RESOLVED_HOLD_MS=5200;
    private static final class Held {final ScanSnapshot.Label label;final long at;Held(ScanSnapshot.Label l,long at){label=l;this.at=at;}}
    private final Map<Long,Held> heldDetections=new HashMap<>();private final Map<Long,Held> heldResolved=new HashMap<>();private List<ScanSnapshot.Label> renderedLabels=Collections.emptyList();
    private static final int BLUE=0xFF7DD3FC,WHITE=0xFFF7FAFC,SECONDARY=0xFFD4DEE5,AMBER=0xFFFFD483,CORAL=0xFFFF8F86;
    public ScanOverlay(Context c,Selection selection){super(c);this.selection=selection;d=getResources().getDisplayMetrics().density;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setLayerType(LAYER_TYPE_SOFTWARE,null);}
    public void show(ScanSnapshot value){
        snapshot=value;long now=SystemClock.elapsedRealtime();
        if(value.status.toLowerCase(Locale.ROOT).contains("paused")||value.status.toLowerCase(Locale.ROOT).contains("unavailable")){heldDetections.clear();heldResolved.clear();}
        for(ScanSnapshot.Label l:value.labels){heldDetections.put(l.id,new Held(l,now));if(l.phase==Phase.RESOLVED&&l.match.medicine!=null)heldResolved.put(l.id,new Held(l,now));}
        heldDetections.entrySet().removeIf(e->now-e.getValue().at>DETECTION_HOLD_MS);
        heldResolved.entrySet().removeIf(e->now-e.getValue().at>RESOLVED_HOLD_MS);
        invalidate();
    }
    public void doses(Map<String,String> labels){doseLabels.clear();doseLabels.putAll(labels);invalidate();}
    private void text(Canvas c,String s,float x,float y,float size,int color,boolean bold){paint.clearShadowLayer();paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextSize(size*d);paint.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));c.drawText(s,x,y,paint);}
    private String fit(String s,float width,float size){paint.setTextSize(size*d);if(paint.measureText(s)<=width)return s;String v=s;while(v.length()>1&&paint.measureText(v+"…")>width)v=v.substring(0,v.length()-1);return v+"…";}
    private String compact(String raw){String s=raw==null?"":raw.replace('\n',' ').replaceAll("\\s+"," ").trim();return s.length()>54?s.substring(0,53)+"…":s;}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);targets.clear();float w=getWidth(),h=getHeight();
        long now=SystemClock.elapsedRealtime();heldDetections.entrySet().removeIf(e->now-e.getValue().at>DETECTION_HOLD_MS);heldResolved.entrySet().removeIf(e->now-e.getValue().at>RESOLVED_HOLD_MS);
        LinkedHashMap<Long,ScanSnapshot.Label> merged=new LinkedHashMap<>();
        for(Map.Entry<Long,Held> e:heldDetections.entrySet())merged.put(e.getKey(),e.getValue().label);
        for(Map.Entry<Long,Held> e:heldResolved.entrySet())merged.put(e.getKey(),e.getValue().label);
        for(ScanSnapshot.Label l:snapshot.labels)merged.put(l.id,l);
        renderedLabels=new ArrayList<>(merged.values());
        if(renderedLabels.isEmpty()){drawReticle(c,w,h);return;}
        Map<Long,ScanSnapshot.Label> byId=new HashMap<>();for(ScanSnapshot.Label l:renderedLabels)byId.put(l.id,l);
        for(Alert a:snapshot.alerts){if(a.priority>2)continue;ScanSnapshot.Label l=byId.get(a.left),r=byId.get(a.right);if(l==null||r==null)continue;paint.setColor(a.priority==0?CORAL:AMBER);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*d);paint.setPathEffect(new DashPathEffect(new float[]{8*d,6*d},0));Path p=new Path();p.moveTo(l.x*w,l.y*h);p.quadTo((l.x+r.x)*w/2,Math.min(l.y,r.y)*h-54*d,r.x*w,r.y*h);c.drawPath(p,paint);paint.setPathEffect(null);}
        List<RectF> placed=new ArrayList<>();
        for(ScanSnapshot.Label l:renderedLabels){
            boolean resolved=l.phase==Phase.RESOLVED&&l.match.medicine!=null;
            boolean stabilizing=l.phase==Phase.STABILIZING&&l.provisional!=null;
            int accent=resolved?BLUE:(stabilizing?AMBER:0xBFFFFFFF);
            for(Alert a:snapshot.alerts)if(a.priority<=2&&(a.left==l.id||a.right==l.id))accent=a.priority==0?CORAL:AMBER;
            RectF box=new RectF(l.box.l*w,l.box.t*h,l.box.r*w,l.box.b*h);
            if(!resolved&&!stabilizing){drawDetectedRegion(c,box,accent);drawOcrChip(c,box,compact(l.text),w,h);targets.put(l.id,box);continue;}
            paint.clearShadowLayer();paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(resolved?2.4f*d:1.8f*d);paint.setColor(accent);c.drawRoundRect(box,12*d,12*d,paint);
            float cw=Math.min(304*d,w-28*d),ch=(resolved?128:112)*d;
            float x=Math.max(14*d,Math.min(w-cw-14*d,l.x*w-cw/2));
            float preferAbove=l.y*h-ch-24*d;float y=preferAbove>112*d?preferAbove:Math.min(h-236*d-ch,l.y*h+28*d);
            y=Math.max(112*d,Math.min(h-218*d-ch,y));RectF card=new RectF(x,y,x+cw,y+ch);
            for(RectF previous:placed){if(RectF.intersects(previous,card)){float down=previous.bottom-card.top+10*d;card.offset(0,down);}}
            if(card.bottom>h-205*d){card.offset(0,(h-205*d)-card.bottom);}if(card.top<104*d){card.offset(0,104*d-card.top);}
            placed.add(card);targets.put(l.id,card);
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xF2111820);paint.setShadowLayer(22*d,0,7*d,0x77000000);c.drawRoundRect(card,22*d,22*d,paint);paint.clearShadowLayer();
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.2f*d);paint.setColor(0x55FFFFFF);c.drawRoundRect(card,22*d,22*d,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(accent);c.drawRoundRect(new RectF(card.left,card.top,card.left+5*d,card.bottom),3*d,3*d,paint);
            float px=l.x*w,py=l.y*h;paint.setColor(accent);paint.setStrokeWidth(1.8f*d);c.drawLine(px,py,Math.max(card.left+24*d,Math.min(card.right-24*d,px)),py<card.top?card.top:card.bottom,paint);paint.setStyle(Paint.Style.FILL);c.drawCircle(px,py,5*d,paint);
            String eyebrow=resolved?(l.anchored?"MEDICINE · AR LOCKED":"MEDICINE · TRACKED"):"READING LABEL · HOLD STEADY";
            text(c,eyebrow,card.left+18*d,card.top+25*d,10,accent,true);
            Medicine m=resolved?l.match.medicine:l.provisional;text(c,fit(m.name,cw-36*d,20),card.left+18*d,card.top+56*d,20,WHITE,true);
            text(c,fit(m.composition(),cw-36*d,12),card.left+18*d,card.top+79*d,12,SECONDARY,false);
            if(resolved){String dose=doseLabels.get(m.id);String footer="Tap for details"+(dose==null?"":"   ·   "+dose);text(c,fit(footer,cw-36*d,10),card.left+18*d,card.top+105*d,10,0xFFAEBCC6,false);}
            else text(c,"Confirming repeated OCR read…",card.left+18*d,card.top+101*d,10,0xFFAEBCC6,false);
        }
    }
    private void drawOcrChip(Canvas c,RectF box,String value,float w,float h){
        if(value.isEmpty())return;String label="TEXT · "+value;float size=10f;paint.setTextSize(size*d);float tw=Math.min(paint.measureText(label)+18*d,w-24*d);float x=Math.max(12*d,Math.min(w-tw-12*d,box.left));float chipH=28*d;float y=box.top-chipH-7*d;if(y<102*d)y=Math.min(h-205*d-chipH,box.bottom+7*d);RectF chip=new RectF(x,y,x+tw,y+chipH);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xD9121820);paint.setShadowLayer(10*d,0,3*d,0x55000000);c.drawRoundRect(chip,12*d,12*d,paint);paint.clearShadowLayer();paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1*d);paint.setColor(0x44FFFFFF);c.drawRoundRect(chip,12*d,12*d,paint);text(c,fit(label,tw-16*d,size),chip.left+8*d,chip.top+18*d,size,0xFFE6EDF3,true);
    }
    private void drawDetectedRegion(Canvas c,RectF box,int color){paint.clearShadowLayer();paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*d);paint.setColor(color);float len=Math.min(19*d,Math.min(box.width(),box.height())*.35f);for(float[] a:new float[][]{{box.left,box.top,1,1},{box.right,box.top,-1,1},{box.left,box.bottom,1,-1},{box.right,box.bottom,-1,-1}}){c.drawLine(a[0],a[1],a[0]+len*a[2],a[1],paint);c.drawLine(a[0],a[1],a[0],a[1]+len*a[3],paint);} }
    private void drawReticle(Canvas c,float w,float h){float l=w*.13f,r=w*.87f,t=h*.30f,b=h*.61f;paint.setStrokeWidth(2*d);paint.setColor(0xAAFFFFFF);paint.setStyle(Paint.Style.STROKE);float len=28*d;for(float[] a:new float[][]{{l,t,1,1},{r,t,-1,1},{l,b,1,-1},{r,b,-1,-1}}){c.drawLine(a[0],a[1],a[0]+len*a[2],a[1],paint);c.drawLine(a[0],a[1],a[0],a[1]+len*a[3],paint);}}
    @Override public boolean onTouchEvent(MotionEvent event){if(event.getAction()==MotionEvent.ACTION_UP){for(ScanSnapshot.Label l:renderedLabels){RectF box=targets.get(l.id);if(box!=null&&box.contains(event.getX(),event.getY())){performClick();selection.select(l);return true;}}}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
