package org.medilens;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.ar.core.*;
import org.medilens.core.*;
import org.medilens.core.Domain.*;
import org.medilens.data.*;
import org.medilens.scan.*;
import org.medilens.ui.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static org.medilens.ui.Ui.*;
public final class MainActivity extends Activity implements NativeScanner.Listener {
    private GLSurfaceView camera;private ScanOverlay overlay;private NativeScanner scanner;private Session session;
    private KnowledgeStore knowledge;private RegimenStore regimens;private TextView status,subtitle;private Button results,pause;
    private boolean installRequested=false,active=false,paused=false,resumed=false,reviewing=false;
    private volatile boolean destroyed=false;
    private ScanSnapshot snapshot=ScanSnapshot.empty("Starting camera");
    private ScanSnapshot lastUsefulSnapshot=null,lastResolvedSnapshot=null;
    private long lastUsefulSnapshotAt=0,lastResolvedSnapshotAt=0;
    private static final long REVIEW_RESOLVED_MEMORY_MS=5000L;
    private static final long REVIEW_TEXT_MEMORY_MS=3000L;
    private final Handler main=new Handler(Looper.getMainLooper());private long lastDoseRefresh=0;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(INK);
        try{knowledge=new KnowledgeStore(this);}catch(Exception e){fatal("Medicine database unavailable","The bundled knowledge could not be opened. Reinstall this build; scanning has been disabled.");return;}
        try{regimens=new RegimenStore(this);}catch(Exception e){regimens=null;}
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(INK);
        root.setOnApplyWindowInsetsListener((v,i)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        camera=new GLSurfaceView(this);camera.setEGLContextClientVersion(2);camera.setPreserveEGLContextOnPause(true);
        scanner=new NativeScanner(this,knowledge.medicines,knowledge.interactions,this);camera.setRenderer(scanner);root.addView(camera,new FrameLayout.LayoutParams(-1,-1));
        overlay=new ScanOverlay(this,this::showLabel);root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));

        // Minimal camera chrome: title + privacy state + one settings entry point.
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(this,18),dp(this,14),dp(this,18),dp(this,12));
        android.graphics.drawable.GradientDrawable topBg=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xD9090B10,0x8A090B10,0x00090B10});top.setBackground(topBg);
        LinearLayout brand=column(this);TextView name=text(this,"MediLens",28,Color.WHITE,true);brand.addView(name);TextView tagline=text(this,"Private · on-device medicine lens",12,0xFFD1D6DE,false);brand.addView(tagline);top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView device=text(this,"●  ON DEVICE",9,MINT,true);device.setGravity(Gravity.CENTER);device.setPadding(dp(this,11),0,dp(this,11),0);android.graphics.drawable.GradientDrawable deviceBg=bg(0xA01C222B,dp(this,18));deviceBg.setStroke(dp(this,1),0x26FFFFFF);device.setBackground(deviceBg);top.addView(device,new LinearLayout.LayoutParams(-2,dp(this,36)));
        TextView gear=iconButton(this,"⚙",this::showSettings);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(this,44),dp(this,44));gp.leftMargin=dp(this,10);top.addView(gear,gp);root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

        // Floating bottom glass panel. Secondary pages now live behind the gear button.
        LinearLayout bottom=column(this);bottom.setPadding(dp(this,16),dp(this,15),dp(this,16),dp(this,14));android.graphics.drawable.GradientDrawable bottomBg=bg(0xEE141820,dp(this,28));bottomBg.setStroke(dp(this,1),0x32FFFFFF);bottom.setBackground(bottomBg);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout title=column(this);subtitle=text(this,"Scan a medicine",19,Color.WHITE,true);title.addView(subtitle);status=text(this,"Starting camera…",12,MUTED,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);title.addView(status);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));pause=button(this,"Pause",false,this::togglePause);pause.setSingleLine(true);pause.setTextSize(14);pause.setMinWidth(0);LinearLayout.LayoutParams pauseParams=new LinearLayout.LayoutParams(dp(this,104),dp(this,44));pauseParams.leftMargin=dp(this,12);row.addView(pause,pauseParams);add(bottom,row,13);
        results=button(this,"Review scan",true,this::showResults);add(bottom,results,0);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);bp.setMargins(dp(this,12),0,dp(this,12),dp(this,12));root.addView(bottom,bp);setContentView(root);
        if(!getPreferences(0).getBoolean("onboarded",false))main.post(this::onboarding);
    }
    private void showSettings(){
        Ui.Sheet s=new Ui.Sheet(this,"MediLens","Everything that isn't the live camera lives here.");
        add(s.body,button(this,"My plan",false,()->{s.dialog.dismiss();showPlan();}),10);
        add(s.body,button(this,"Supported medicines · "+knowledge.medicines.size(),false,()->{s.dialog.dismiss();showCatalog();}),10);
        add(s.body,button(this,"Scanning guide",false,()->{s.dialog.dismiss();showHelp();}),18);
        add(s.body,text(this,"PRIVACY",11,MINT,true),8);
        add(s.body,text(this,"Camera frames and OCR stay on this device. Your plan is stored locally and encrypted.",14,MUTED,false),14);
        add(s.body,button(this,"Erase my saved plan",false,()->new AlertDialog.Builder(this).setTitle("Erase your saved plan?").setMessage("All local occurrences and dose events will be deleted.").setPositiveButton("Erase",(d,w)->{if(regimens!=null){regimens.clearAll();refreshDoses();}toast("Local plan erased");}).setNegativeButton("Cancel",null).show()),8);
        s.show();
    }
    @Override protected void onResume(){super.onResume();resumed=true;if(scanner!=null)startCamera();}
    private void startCamera(){
        if(active || !resumed)return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},42);return;}
        try{
            if(session==null){
                if(ArCoreApk.getInstance().requestInstall(this,!installRequested)==ArCoreApk.InstallStatus.INSTALL_REQUESTED){installRequested=true;status.setText("Finish installing Google Play Services for AR, then return");return;}
                session=new Session(this);com.google.ar.core.Config config=new com.google.ar.core.Config(session);config.setFocusMode(com.google.ar.core.Config.FocusMode.AUTO);
                if(session.isDepthModeSupported(com.google.ar.core.Config.DepthMode.AUTOMATIC))config.setDepthMode(com.google.ar.core.Config.DepthMode.AUTOMATIC);
                config.setPlaneFindingMode(com.google.ar.core.Config.PlaneFindingMode.DISABLED);session.configure(config);
            }
            scanner.attach(session);session.resume();camera.onResume();active=true;
        }catch(Exception e){status.setText("AR unavailable · open Help to retry");new AlertDialog.Builder(this).setTitle("Camera could not start").setMessage("Use an ARCore-supported Android phone, grant camera access, and install Google Play Services for AR. The app needs the camera to scan.").setPositiveButton("Retry",(d,w)->retry()).setNegativeButton("Close",null).show();}
    }
    private void retry(){scanner.invalidate();if(active){camera.queueEvent(scanner::releaseAnchors);camera.onPause();session.pause();active=false;}if(session!=null){session.close();session=null;}installRequested=false;startCamera();}
    @Override protected void onPause(){resumed=false;if(scanner!=null){scanner.invalidate();camera.queueEvent(scanner::releaseAnchors);camera.onPause();if(session!=null&&active)session.pause();active=false;overlay.show(ScanSnapshot.empty("Camera paused"));}super.onPause();}
    @Override protected void onDestroy(){destroyed=true;main.removeCallbacksAndMessages(null);if(scanner!=null)scanner.close();if(session!=null)session.close();if(knowledge!=null)knowledge.close();if(regimens!=null)regimens.close();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);if(code==42){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startCamera();else{status.setText("Camera access is needed to scan");new AlertDialog.Builder(this).setTitle("Allow camera access").setMessage("MediLens reads package text on this phone. It does not upload camera frames.").setPositiveButton("Open settings",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNegativeButton("Cancel",null).show();}}}
    private void togglePause(){
        paused=!paused;
        scanner.setScanning(!paused);
        pause.setText(paused?"Resume":"Pause");
        snapshot=ScanSnapshot.empty(paused?"Scanning paused":"Point at a package");
        overlay.show(snapshot);
        status.setText(snapshot.status);
        if(!paused)status.setText("Reacquiring label…");
    }

    @Override public void onSnapshot(ScanSnapshot value){
        if(destroyed)return;
        main.post(()->{
            if(destroyed||!resumed)return;

            long now=SystemClock.elapsedRealtime();

            /*
             * Review mode deliberately freezes the visible scan.
             *
             * The scanner can keep working in the background, but its rapidly
             * changing live snapshots are not allowed to rewrite what the user
             * is currently reviewing.
             */
            if(reviewing){
                if(now-lastDoseRefresh>1000){
                    lastDoseRefresh=now;
                    refreshDoses();
                }
                return;
            }

            snapshot=value;
            rememberReviewSnapshot(value,now);
            overlay.show(value);

            if(!status.getText().toString().equals(value.status))status.setText(value.status);

            ScanSnapshot review=chooseReviewSnapshot(now);
            int alerts=review.alerts.size();
            int confirmed=countResolved(review);

            if(alerts>0){
                results.setText(alerts+" safety item"+(alerts==1?"":"s")+" to review");
            }else if(confirmed>0){
                results.setText("Review scan · "+confirmed+" confirmed");
            }else if(!review.labels.isEmpty()){
                results.setText("Review scan · text detected");
            }else{
                results.setText("Review scan");
            }

            if(now-lastDoseRefresh>1000){
                lastDoseRefresh=now;
                refreshDoses();
            }
        });
    }

    private void rememberReviewSnapshot(ScanSnapshot value,long now){
        if(value==null||value.labels.isEmpty())return;

        lastUsefulSnapshot=value;
        lastUsefulSnapshotAt=now;

        if(countResolved(value)>0){
            lastResolvedSnapshot=value;
            lastResolvedSnapshotAt=now;
        }
    }

    private ScanSnapshot chooseReviewSnapshot(long now){
        /*
         * Current confirmed medicine is always preferred.
         */
        if(snapshot!=null&&countResolved(snapshot)>0)return snapshot;

        /*
         * If live OCR briefly drops every label, keep the last confirmed scan
         * available for a few seconds instead of opening an empty review.
         *
         * We only use remembered confirmed results when the current frame is
         * empty, so an old medicine cannot silently replace a new unresolved
         * package the user is now pointing at.
         */
        if((snapshot==null||snapshot.labels.isEmpty())&&
                lastResolvedSnapshot!=null&&
                now-lastResolvedSnapshotAt<=REVIEW_RESOLVED_MEMORY_MS){
            return lastResolvedSnapshot;
        }

        if(snapshot!=null&&!snapshot.labels.isEmpty())return snapshot;

        if(lastUsefulSnapshot!=null&&
                now-lastUsefulSnapshotAt<=REVIEW_TEXT_MEMORY_MS){
            return lastUsefulSnapshot;
        }

        return snapshot==null?ScanSnapshot.empty("No scan to review"):snapshot;
    }

    private static int countResolved(ScanSnapshot value){
        if(value==null)return 0;
        int count=0;
        for(ScanSnapshot.Label label:value.labels)if(label.match!=null&&label.match.medicine!=null)count++;
        return count;
    }

    private static String reviewPreview(String raw){
        if(raw==null)return "Unresolved label";
        String text=raw.replace('\n',' ').replace('\r',' ').trim().replaceAll("\\s+"," ");
        if(text.isEmpty())return "Unresolved label";
        return text.length()>52?text.substring(0,49)+"…":text;
    }
    private void refreshDoses(){if(regimens==null)return;try{Map<String,String> labels=new HashMap<>();for(RegimenStore.Entry e:regimens.entries()){DoseState state=DoseEngine.state(Instant.now(),e.due,e.event);if(!labels.containsKey(e.medicineId)||labels.get(e.medicineId).equals("TAKEN")||labels.get(e.medicineId).equals("SKIPPED"))labels.put(e.medicineId,state.name());}overlay.doses(labels);}catch(Exception e){regimens.close();regimens=null;toast("Schedule unavailable; scanning remains available");}}
    private void onboarding(){Ui.Sheet s=new Ui.Sheet(this,"Meet your medicine lens","A camera view with information attached to the label.");add(s.body,text(this,"01  Frame the full label",19,Color.WHITE,true),8);add(s.body,text(this,"Keep packages separate and steady. The brand, ingredient and strength must be readable together.",15,MUTED,false),22);add(s.body,text(this,"02  Review what is identified",19,Color.WHITE,true),8);add(s.body,text(this,"Tap a label for its composition and source. Unsupported or unclear text stays unresolved.",15,MUTED,false),22);add(s.body,text(this,"03  Keep your judgment",19,Color.WHITE,true),8);add(s.body,text(this,"This research prototype has an expanded but incomplete supported catalog. Missing interaction information does not mean safe. Confirm medicines with a pharmacist; this app does not prescribe or recommend doses.",15,MUTED,false),22);add(s.body,button(this,"Start scanning",true,()->{getPreferences(0).edit().putBoolean("onboarded",true).apply();s.dialog.dismiss();}),12);s.show();}
    private void showLabel(ScanSnapshot.Label label){if(label.match.medicine==null){Ui.Sheet s=new Ui.Sheet(this,"Not identified",label.match.reason);add(s.body,text(this,"Make the full brand, ingredient and strength readable in one label region. Check Supported for the exact formulations in this build.",16,MUTED,false),16);s.show();return;}showMedicine(label.match.medicine);}
    private void showMedicine(Medicine m){Ui.Sheet s=new Ui.Sheet(this,m.name,m.form);add(s.body,text(this,"ACTIVE INGREDIENT",11,MINT,true),10);add(s.body,text(this,m.composition(),20,Color.WHITE,true),20);add(s.body,text(this,"Identification and interaction severity are separate. Confirm this identity against the physical label.",14,MUTED,false),20);add(s.body,button(this,"Add an occurrence to my plan",true,()->{s.dialog.dismiss();pickDate(m);}),20);add(s.body,text(this,"SOURCE",11,MINT,true),8);TextView source=text(this,knowledge.source(m.sourceId),13,MUTED,false);source.setTextIsSelectable(true);add(s.body,source,20);s.show();}
    private void showResults(){
        final long now=SystemClock.elapsedRealtime();
        final ScanSnapshot captured=chooseReviewSnapshot(now);
        final int confirmed=countResolved(captured);

        /*
         * Freeze the CAMERA UI at exactly the snapshot the user chose to
         * review. Incoming live OCR is ignored by onSnapshot until this sheet
         * closes, so the results cannot mutate underneath the user.
         */
        reviewing=true;
        snapshot=captured;
        overlay.show(captured);
        status.setText("Scan frozen for review");

        String summary;
        if(captured.labels.isEmpty()){
            summary="No stable label captured";
        }else if(confirmed>0){
            summary=confirmed+" confirmed medicine"+(confirmed==1?"":"s")+" · local analysis";
        }else{
            summary="Medicine-like text detected · not yet confirmed";
        }

        Ui.Sheet s=new Ui.Sheet(this,"Review scan",summary);

        if(captured.labels.isEmpty()){
            add(s.body,text(this,"Nothing stable is available to review yet. Return to the camera, keep one medicine package centered, and hold the brand, ingredient and strength steady in view.",16,MUTED,false),20);
        }

        if(!captured.alerts.isEmpty()){
            add(s.body,text(this,"SAFETY",11,MINT,true),8);
        }

        for(Alert a:captured.alerts){
            add(s.body,text(this,a.severity+"  ·  "+a.title,18,a.priority==0?0xFFFF9289:0xFFFFCF80,true),8);
            add(s.body,text(this,a.detail,15,MUTED,false),10);
            if(!a.sourceId.isEmpty()){
                add(s.body,button(this,"View evidence",false,()->{
                    Ui.Sheet evidence=new Ui.Sheet(this,"Evidence",a.title);
                    add(evidence.body,text(this,knowledge.source(a.sourceId),14,MUTED,false),12);
                    evidence.show();
                }),16);
            }
        }

        if(confirmed>0){
            add(s.body,text(this,"IDENTIFIED",11,MINT,true),10);
            for(ScanSnapshot.Label l:captured.labels){
                if(l.match==null||l.match.medicine==null)continue;
                Medicine medicine=l.match.medicine;
                add(s.body,button(this,medicine.name+"  ·  "+medicine.composition(),false,()->showLabel(l)),10);
            }
        }

        int unresolved=0;
        for(ScanSnapshot.Label l:captured.labels){
            if(l.match==null||l.match.medicine==null)unresolved++;
        }

        if(unresolved>0){
            add(s.body,text(this,"NOT CONFIRMED",11,0xFFFFCF80,true),confirmed>0?18:10);
            add(s.body,text(this,"MediLens could read medicine-like text here, but it did not have enough evidence to identify a supported formulation. These are not medicine identifications.",14,MUTED,false),12);

            int shown=0;
            for(ScanSnapshot.Label l:captured.labels){
                if(l.match!=null&&l.match.medicine!=null)continue;
                if(shown>=3)break;

                final ScanSnapshot.Label unresolvedLabel=l;
                add(s.body,button(this,reviewPreview(l.text),false,()->showLabel(unresolvedLabel)),8);
                shown++;
            }

            if(unresolved>shown){
                add(s.body,text(this,(unresolved-shown)+" additional unresolved region"+(unresolved-shown==1?"":"s")+" hidden to keep this review readable.",13,MUTED,false),10);
            }
        }

        add(s.body,text(this,"A scan identifies printed packaging, not what you are taking. The supported catalog and interaction data are incomplete; no recorded interaction does not establish safety.",13,MUTED,false),18);

        /*
         * Live overlay updates resume only after the review sheet has closed.
         * Manual Pause remains respected.
         */
        s.dialog.setOnDismissListener(dialog->{
            reviewing=false;
            if(destroyed||!resumed)return;
            status.setText(paused?"Scanning paused":"Reacquiring label…");
        });

        s.show();
    }
    private void showCatalog(){Ui.Sheet s=new Ui.Sheet(this,"Supported medicines",knowledge.medicines.size()+" source-checked formulations · including Indian brands");add(s.body,text(this,"Recognition is intentionally strict: brand, ingredient and strength must agree. The catalog is larger now but still incomplete. Interaction coverage is separate from recognition coverage; missing interaction data never means a combination is safe.",14,MUTED,false),20);for(Medicine m:knowledge.medicines)add(s.body,button(this,m.name,false,()->showMedicine(m)),10);s.show();}
    private void showPlan(){if(regimens==null){toast("Encrypted schedule storage unavailable");return;}Ui.Sheet s=new Ui.Sheet(this,"My plan","Only occurrences you enter. No dose recommendations or automatic reminders.");
        try{List<RegimenStore.Entry> entries=regimens.entries();if(entries.isEmpty())add(s.body,text(this,"Your plan is empty. Choose a supported medicine and enter a time from your own regimen.",15,MUTED,false),20);
            for(RegimenStore.Entry e:entries){Medicine med=findMedicine(e.medicineId);String name=med==null?"Unavailable medicine":med.name;add(s.body,text(this,name,18,Color.WHITE,true),4);String time=e.due.atZone(ZoneId.of(e.zone)).format(DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm z"));add(s.body,text(this,time+"\n"+DoseEngine.state(Instant.now(),e.due,e.event).name(),14,MUTED,false),10);
                if(e.event!=DoseState.TAKEN&&e.event!=DoseState.SKIPPED){LinearLayout actions=new LinearLayout(this);Button taken=button(this,"Mark taken",false,()->confirmEvent(e,DoseState.TAKEN,s));Button skipped=button(this,"Mark skipped",false,()->confirmEvent(e,DoseState.SKIPPED,s));actions.addView(taken,new LinearLayout.LayoutParams(0,dp(this,48),1));actions.addView(skipped,new LinearLayout.LayoutParams(0,dp(this,48),1));add(s.body,actions,8);}
                add(s.body,button(this,"Delete occurrence",false,()->new AlertDialog.Builder(this).setTitle("Delete this occurrence?").setMessage("This removes its local event history too.").setPositiveButton("Delete",(d,w)->{regimens.delete(e.id);s.dialog.dismiss();showPlan();}).setNegativeButton("Cancel",null).show()),22);
            }
        }catch(Exception e){add(s.body,text(this,"Your plan could not be decrypted. Existing records have been preserved.",15,0xFFFF9289,false),20);}
        add(s.body,button(this,"Add from supported medicines",true,()->{s.dialog.dismiss();showCatalog();}),14);s.show();
    }
    private void confirmEvent(RegimenStore.Entry e,DoseState event,Ui.Sheet s){new AlertDialog.Builder(this).setTitle(event==DoseState.TAKEN?"Record that you took this?":"Record this as skipped?").setMessage("This records your report. It is not advice to take or skip a medicine.").setPositiveButton("Record",(d,w)->{try{regimens.save(new RegimenStore.Entry(e.id,e.medicineId,e.due,e.zone,event));s.dialog.dismiss();refreshDoses();showPlan();}catch(Exception ex){toast("Event could not be saved");}}).setNegativeButton("Cancel",null).show();}
    private void pickDate(Medicine m){if(regimens==null){toast("Schedule storage unavailable");return;}LocalDate today=LocalDate.now();new DatePickerDialog(this,(view,y,month,day)->{LocalDate date=LocalDate.of(y,month+1,day);LocalTime time=LocalTime.now();new TimePickerDialog(this,(v,h,min)->{try{ZoneId zone=ZoneId.systemDefault();Instant due=DoseEngine.localOccurrence(date,LocalTime.of(h,min),zone);regimens.save(new RegimenStore.Entry(UUID.randomUUID().toString(),m.id,due,zone.getId(),DoseState.UNKNOWN));refreshDoses();showPlan();}catch(IllegalArgumentException e){toast(e.getMessage());}catch(Exception e){toast("Occurrence could not be saved");}},time.getHour(),time.getMinute(),true).show();},today.getYear(),today.getMonthValue()-1,today.getDayOfMonth()).show();}
    private Medicine findMedicine(String id){for(Medicine m:knowledge.medicines)if(m.id.equals(id))return m;return null;}
    private void showHelp(){Ui.Sheet s=new Ui.Sheet(this,"Scanning guide","Designed for an ARCore-supported Android phone.");add(s.body,text(this,"Keep the complete label in view, use even light and leave space between packages. Hold still for repeated text readings. Depth labels attach only when a surface estimate is available; screen labels are clearly marked. Move slowly to keep tracking stable.",15,MUTED,false),20);add(s.body,text(this,"This preview uses OCR text regions. A trained package detector has not been supplied. Labels can fail when brand and composition are in separate regions. Actual accuracy and phone latency are not yet measured.",14,MUTED,false),20);add(s.body,text(this,"Camera frames and text stay on this device. OCR is bundled. Internet is needed for initial installation and AR Services provisioning, not normal scanning.",14,MUTED,false),20);add(s.body,button(this,"Retry camera",true,()->{s.dialog.dismiss();retry();}),12);add(s.body,button(this,"Privacy · erase my plan",false,()->new AlertDialog.Builder(this).setTitle("Erase your saved plan?").setMessage("All local occurrences and dose events will be deleted.").setPositiveButton("Erase",(d,w)->{if(regimens!=null){regimens.clearAll();refreshDoses();}toast("Local plan erased");}).setNegativeButton("Cancel",null).show()),12);s.show();}
    private void fatal(String title,String message){LinearLayout root=column(this);root.setPadding(dp(this,28),dp(this,64),dp(this,28),dp(this,28));root.setBackgroundColor(INK);add(root,text(this,title,26,Color.WHITE,true),20);add(root,text(this,message,16,MUTED,false),24);setContentView(root);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
