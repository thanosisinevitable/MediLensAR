package org.medilens.data;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.security.keystore.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.json.JSONObject;
import org.medilens.core.Domain.DoseState;
/** SQLite records encrypted with a non-exportable Android Keystore AES key. No cloud backup. */
public final class RegimenStore extends SQLiteOpenHelper {
    private final SecretKey key;
    public static final class Entry {
        public final String id,medicineId,zone;public final Instant due;public final DoseState event;
        public Entry(String id,String medicine,Instant due,String zone,DoseState event){this.id=id;medicineId=medicine;this.due=due;this.zone=zone;this.event=event;}
    }
    public RegimenStore(Context c)throws Exception{
        super(c,"regimen.db",null,1);
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias("medilens-regimen-v1")){KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder("medilens-regimen-v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();}
        key=(SecretKey)store.getKey("medilens-regimen-v1",null);
    }
    public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE Regimen(id TEXT PRIMARY KEY, iv BLOB NOT NULL,payload BLOB NOT NULL)");db.execSQL("CREATE TABLE DoseEvent(id INTEGER PRIMARY KEY AUTOINCREMENT,regimen_id TEXT NOT NULL,iv BLOB NOT NULL,payload BLOB NOT NULL)");}
    public void onUpgrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("Unsupported regimen migration; records preserved");}
    public synchronized void save(Entry entry)throws Exception{
        JSONObject o=new JSONObject();o.put("medicineId",entry.medicineId);o.put("due",entry.due.toString());o.put("zone",entry.zone);o.put("event",entry.event.name());o.put("recordedAt",Instant.now().toString());
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);c.updateAAD(entry.id.getBytes(StandardCharsets.UTF_8));byte[] encrypted=c.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
        ContentValues v=new ContentValues();v.put("id",entry.id);v.put("iv",c.getIV());v.put("payload",encrypted);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{if(db.insertWithOnConflict("Regimen",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new SQLiteException("Regimen save failed");if(entry.event==DoseState.TAKEN||entry.event==DoseState.SKIPPED){v.remove("id");v.put("regimen_id",entry.id);db.insertOrThrow("DoseEvent",null,v);}db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized List<Entry> entries()throws Exception{
        List<Entry> out=new ArrayList<>();try(Cursor rows=getReadableDatabase().rawQuery("SELECT id,iv,payload FROM Regimen",null)){while(rows.moveToNext()){
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,rows.getBlob(1)));c.updateAAD(rows.getString(0).getBytes(StandardCharsets.UTF_8));JSONObject o=new JSONObject(new String(c.doFinal(rows.getBlob(2)),StandardCharsets.UTF_8));out.add(new Entry(rows.getString(0),o.getString("medicineId"),Instant.parse(o.getString("due")),o.getString("zone"),DoseState.valueOf(o.getString("event"))));
        }}out.sort(Comparator.comparing(e->e.due));return out;
    }
    public synchronized void delete(String id){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.delete("DoseEvent","regimen_id=?",new String[]{id});db.delete("Regimen","id=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public synchronized void clearAll(){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.delete("DoseEvent",null,null);db.delete("Regimen",null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}}
}
