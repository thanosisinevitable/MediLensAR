import java.time.*;
import java.util.*;
import org.medilens.core.*;
import org.medilens.core.Domain.*;
/** Synthetic domain fixtures only; never packaged as clinical knowledge. */
public final class CoreTests {
    static int passed,failed;
    static Ingredient x=new Ingredient("test-x","TESTINGREDIENTX","10","mg"),y=new Ingredient("test-y","TESTINGREDIENTY","20","mg"),z=new Ingredient("test-z","TESTINGREDIENTZ","30","mg");
    static Medicine a=medicine("test-a","TESTALPHA",x),b=medicine("test-b","TESTBETA",x,y),c=medicine("test-c","TESTGAMMA",z);
    static Medicine medicine(String id,String brand,Ingredient...i){return new Medicine(id,brand,"test form","test-source",Arrays.asList(brand),Collections.emptyList(),Arrays.asList("PLUS"),Arrays.asList(i));}
    static void check(boolean v){if(!v)throw new AssertionError();}
    static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static void throwsError(Runnable action){try{action.run();throw new AssertionError("Expected error");}catch(IllegalArgumentException expected){}}
    static void test(String name,Runnable run){try{run.run();passed++;System.out.println("PASS "+name);}catch(Throwable e){failed++;System.out.println("FAIL "+name+" "+e);}}
    static String labelA(){return "TESTALPHA TESTINGREDIENTX 10 MG";}
    static Box box(){return new Box(.1f,.2f,.4f,.6f);}
    static Observation obs(String text,long t){return new Observation(box(),text,t);}
    static Track track(long id,Medicine m){Track t=new Track(id,obs("",0));t.match=new Match(m,Confidence.HIGH,"test");return t;}
    public static void main(String[] args){
        Resolver resolver=new Resolver(Arrays.asList(a,b,c));
        test("normalize casing/spacing",()->equal(Resolver.normalize("  TestAlpha \n   label"),"TESTALPHA LABEL"));
        test("normalize punctuation",()->equal(Resolver.normalize("TESTALPHA (10 mg)"),"TESTALPHA 10 MG"));
        test("preserve decimal strength",()->equal(Resolver.normalize("0,4 mg"),"0.4 MG"));
        test("exact evidence resolves",()->equal(resolver.resolve(labelA()).medicine.id,a.id));
        test("case-insensitive evidence",()->equal(resolver.resolve(labelA().toLowerCase(Locale.ROOT)).medicine.id,a.id));
        test("unsupported brand rejected",()->check(resolver.resolve("UNKNOWN TESTINGREDIENTX 10 MG").medicine==null));
        test("substring brand rejected",()->check(resolver.resolve("TESTALPHABET TESTINGREDIENTX 10 MG").medicine==null));
        test("OCR corruption not overconfident",()->check(resolver.resolve("TESTA1PHA TESTINGREDIENTX 10 MG").medicine==null));
        test("missing strength rejected",()->check(resolver.resolve("TESTALPHA TESTINGREDIENTX").medicine==null));
        test("missing ingredient rejected",()->check(resolver.resolve("TESTALPHA 10 MG").medicine==null));
        test("wrong strength rejected",()->check(resolver.resolve("TESTALPHA TESTINGREDIENTX 100 MG").medicine==null));
        test("extra strength rejected",()->check(resolver.resolve(labelA()+" 20 MG").medicine==null));
        test("wrong unit rejected",()->check(resolver.resolve("TESTALPHA TESTINGREDIENTX 10 MCG").medicine==null));
        test("conflicting ingredient rejected",()->check(resolver.resolve(labelA()+" TESTINGREDIENTZ").medicine==null));
        test("excluded variant rejected",()->check(resolver.resolve(labelA()+" PLUS").medicine==null));
        test("two brands in region rejected",()->check(resolver.resolve(labelA()+" TESTBETA").medicine==null));
        test("combination exact composition",()->equal(resolver.resolve("TESTBETA TESTINGREDIENTX 10 MG TESTINGREDIENTY 20 MG").medicine.id,b.id));
        test("combination missing ingredient",()->check(resolver.resolve("TESTBETA TESTINGREDIENTX 10 MG 20 MG").medicine==null));
        test("ambiguous catalog rejected",()->check(new Resolver(Arrays.asList(a,medicine("another","TESTALPHA",x))).resolve(labelA()).medicine==null));
        test("required variant",()->{Medicine q=new Medicine("q","TESTDELTA","","s",Arrays.asList("TESTDELTA"),Arrays.asList("LIQUID"),Collections.emptyList(),Arrays.asList(x));Resolver r=new Resolver(Arrays.asList(q));check(r.resolve("TESTDELTA TESTINGREDIENTX 10 MG").medicine==null);equal(r.resolve("TESTDELTA LIQUID TESTINGREDIENTX 10 MG").medicine.id,"q");});
        test("empty text unresolved",()->check(resolver.resolve("").medicine==null));
        test("IoU identical",()->equal(box().iou(box()),1f));
        test("IoU disjoint",()->equal(box().iou(new Box(.6f,.7f,.8f,.9f)),0f));
        test("invalid boxes rejected",()->{throwsError(()->new Box(0,0,0,1));throwsError(()->new Box(Float.NaN,0,1,1));});
        test("three observations required",()->{Tracker t=new Tracker(resolver);check(t.update(Arrays.asList(obs(labelA(),100)),100).get(0).match.medicine==null);check(t.update(Arrays.asList(obs(labelA(),800)),800).get(0).match.medicine==null);equal(t.update(Arrays.asList(obs(labelA(),1500)),1500).get(0).match.medicine.id,a.id);});
        test("track ID persists",()->{Tracker t=new Tracker(resolver);long id=t.update(Arrays.asList(obs(labelA(),100)),100).get(0).id;equal(t.update(Arrays.asList(obs(labelA(),800)),800).get(0).id,id);});
        test("temporary loss invalidates identity",()->{Tracker t=new Tracker(resolver);t.update(Arrays.asList(obs(labelA(),100)),100);t.update(Arrays.asList(obs(labelA(),800)),800);long id=t.update(Arrays.asList(obs(labelA(),1500)),1500).get(0).id;check(t.update(Collections.emptyList(),1700).isEmpty());Track reacquired=t.update(Arrays.asList(obs(labelA(),2000)),2000).get(0);equal(reacquired.id,id);check(reacquired.match.medicine==null);});
        test("stale results discarded",()->check(new Tracker(resolver).update(Arrays.asList(obs(labelA(),1)),2000).isEmpty()));
        test("future observation discarded",()->check(new Tracker(resolver).update(Arrays.asList(obs(labelA(),1000)),100).isEmpty()));
        test("stale labels expire",()->{Tracker t=new Tracker(resolver);t.update(Arrays.asList(obs(labelA(),10)),10);check(t.current(2000).isEmpty());});
        test("unsupported breaks consensus",()->{Tracker t=new Tracker(resolver);t.update(Arrays.asList(obs(labelA(),100)),100);t.update(Arrays.asList(obs(labelA(),800)),800);check(t.update(Arrays.asList(obs("UNKNOWN",1500)),1500).get(0).match.medicine==null);equal(t.update(Arrays.asList(obs(labelA(),2000)),2000).get(0).hits,1);});
        test("long gap resets consensus",()->{Tracker t=new Tracker(resolver);t.update(Arrays.asList(obs(labelA(),100)),100);equal(t.update(Arrays.asList(obs(labelA(),1400)),1400).get(0).hits,1);});
        test("separate packages get separate IDs",()->{Tracker t=new Tracker(resolver);List<Track> tracks=t.update(Arrays.asList(obs(labelA(),100),new Observation(new Box(.6f,.2f,.9f,.6f),labelA(),100)),100);equal(tracks.size(),2);check(tracks.get(0).id!=tracks.get(1).id);});
        Interaction interaction=new Interaction("test-ddi","test-y","test-z","MODERATE","Synthetic test interaction","test-source");SafetyEngine engine=new SafetyEngine(Arrays.asList(interaction));
        test("duplicate ingredient",()->check(engine.evaluate(Arrays.asList(track(1,a),track(2,b))).stream().anyMatch(e->e.severity.equals("DUPLICATE"))));
        test("unknown is no data",()->equal(engine.evaluate(Arrays.asList(track(1,a),track(2,c))).get(0).severity,"NO DATA"));
        test("DDI canonical reverse pair",()->equal(SafetyEngine.pair("z","a"),SafetyEngine.pair("a","z")));
        test("combination all pairs evaluated",()->equal(engine.evaluate(Arrays.asList(track(1,b),track(2,c))).size(),2));
        test("known DDI before unknown",()->equal(engine.evaluate(Arrays.asList(track(1,b),track(2,c))).get(0).severity,"MODERATE"));
        test("unresolved excluded from clinical alerts",()->check(engine.evaluate(Arrays.asList(new Track(1,obs("",0)),track(2,b))).isEmpty()));
        test("same med separate packages duplicate",()->equal(engine.evaluate(Arrays.asList(track(1,a),track(2,a))).get(0).severity,"DUPLICATE"));
        test("alerts debounce and expire",()->{SafetyEngine.AlertManager manager=new SafetyEngine.AlertManager();List<Alert> input=engine.evaluate(Arrays.asList(track(1,a),track(2,a)));check(manager.update(input,0).isEmpty());equal(manager.update(input,500).size(),1);check(manager.update(Collections.emptyList(),600).isEmpty());check(manager.update(input,700).isEmpty());});
        test("critical interaction immediate",()->equal(new SafetyEngine.AlertManager().update(engine.evaluate(Arrays.asList(track(1,b),track(2,c))),0).size(),1));
        test("alert deduplication",()->{SafetyEngine.AlertManager m=new SafetyEngine.AlertManager();Alert alert=engine.evaluate(Arrays.asList(track(1,b),track(2,c))).get(0);equal(m.update(Arrays.asList(alert,alert),0).size(),1);});
        Instant due=Instant.parse("2026-09-21T10:00:00Z");
        test("dose unknown",()->equal(DoseEngine.state(due,null,DoseState.UNKNOWN),DoseState.UNKNOWN));
        test("dose upcoming",()->equal(DoseEngine.state(due.minusSeconds(901),due,DoseState.UNKNOWN),DoseState.UPCOMING));
        test("dose early boundary inclusive",()->equal(DoseEngine.state(due.minusSeconds(900),due,DoseState.UNKNOWN),DoseState.DUE));
        test("dose due",()->equal(DoseEngine.state(due,due,DoseState.UNKNOWN),DoseState.DUE));
        test("dose late boundary inclusive",()->equal(DoseEngine.state(due.plusSeconds(1800),due,DoseState.UNKNOWN),DoseState.DUE));
        test("dose overdue",()->equal(DoseEngine.state(due.plusSeconds(1801),due,DoseState.UNKNOWN),DoseState.OVERDUE));
        test("dose taken persists",()->equal(DoseEngine.state(due.plusSeconds(80000),due,DoseState.TAKEN),DoseState.TAKEN));
        test("dose skipped persists",()->equal(DoseEngine.state(due.plusSeconds(80000),due,DoseState.SKIPPED),DoseState.SKIPPED));
        test("timezone conversion",()->equal(DoseEngine.localOccurrence(LocalDate.of(2026,9,21),LocalTime.of(15,30),ZoneId.of("Asia/Kolkata")),due));
        test("DST gap rejected",()->throwsError(()->DoseEngine.localOccurrence(LocalDate.of(2026,3,8),LocalTime.of(2,30),ZoneId.of("America/New_York"))));
        test("DST overlap rejected",()->throwsError(()->DoseEngine.localOccurrence(LocalDate.of(2026,11,1),LocalTime.of(1,30),ZoneId.of("America/New_York"))));
        test("OCR rotation 0",()->check(Arrays.equals(Coordinates.unrotate(20,30,640,480,0),new float[]{20,30})));
        test("OCR rotation 90",()->check(Arrays.equals(Coordinates.unrotate(450,20,640,480,90),new float[]{20,30})));
        test("OCR rotation 180",()->check(Arrays.equals(Coordinates.unrotate(620,450,640,480,180),new float[]{20,30})));
        test("OCR rotation 270",()->check(Arrays.equals(Coordinates.unrotate(30,620,640,480,270),new float[]{20,30})));
        test("invalid rotation",()->throwsError(()->Coordinates.unrotate(0,0,640,480,45)));
        test("affine mapping",()->check(Arrays.equals(Coordinates.view(320,240,640,480,new float[]{0,0,1,0,0,1}),new float[]{.5f,.5f})));
        test("complete synthetic perception to safety",()->{Tracker t=new Tracker(resolver);List<Track> tracks=null;for(int i=1;i<=3;i++)tracks=t.update(Arrays.asList(obs(labelA(),i*700),new Observation(new Box(.6f,.2f,.9f,.6f),"TESTBETA TESTINGREDIENTX 10 MG TESTINGREDIENTY 20 MG",i*700)),i*700);List<Alert> output=engine.evaluate(tracks);check(output.stream().anyMatch(e->e.severity.equals("DUPLICATE")));});
        System.out.println("RESULT "+passed+" passed, "+failed+" failed");if(failed>0)System.exit(1);
    }
}
