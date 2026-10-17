package org.medilens.scan;
import android.util.Log;
import android.os.SystemClock;
import java.util.concurrent.ConcurrentHashMap;
/** Logs stable system codes and exception types only, never OCR, medicine or regimen content. */
public final class Diagnostics {
    public enum Level { DEBUG, INFO, WARN, ERROR }
    private static final boolean DEBUG_ENABLED=false;
    private static final ConcurrentHashMap<String,Long> last=new ConcurrentHashMap<>();
    private Diagnostics(){}
    public static void log(Level level,String code,Throwable error){
        if(level==Level.DEBUG && !DEBUG_ENABLED)return;
        long now=SystemClock.elapsedRealtime();Long previous=last.putIfAbsent(code,now);
        if(previous!=null && now-previous<10000)return;last.put(code,now);
        String value="level="+level+" code="+code+(error==null?"":" type="+error.getClass().getSimpleName());
        int priority=level==Level.ERROR?Log.ERROR:level==Level.WARN?Log.WARN:level==Level.INFO?Log.INFO:Log.DEBUG;
        Log.println(priority,"MediLens",value);
    }
}
