package org.medilens.data;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.medilens.core.Domain.*;
public final class KnowledgeStore implements AutoCloseable {
    private final SQLiteDatabase db;
    public final List<Medicine> medicines=new ArrayList<>();
    public final List<Interaction> interactions=new ArrayList<>();
    public KnowledgeStore(Context context)throws Exception{
        File target=new File(context.getFilesDir(),"knowledge-v1.db");
        String expected;
        try(InputStream in=context.getAssets().open("knowledge.sha256")){expected=new String(readAll(in),java.nio.charset.StandardCharsets.UTF_8).trim();}
        if(!target.exists() || !hash(target).equals(expected)){
            File temp=new File(context.getFilesDir(),"knowledge.tmp");
            try(InputStream in=context.getAssets().open("knowledge.db");FileOutputStream out=new FileOutputStream(temp)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.getFD().sync();}
            if(!hash(temp).equals(expected))throw new IOException("Knowledge checksum mismatch");
            java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        db=SQLiteDatabase.openDatabase(target.getPath(),null,SQLiteDatabase.OPEN_READONLY);
        try{
            if(db.getVersion()!=1)throw new IOException("Unsupported knowledge schema");
            try(Cursor c=db.rawQuery("PRAGMA integrity_check",null)){if(!c.moveToFirst()||!c.getString(0).equals("ok"))throw new IOException("Database integrity failed");}
            try(Cursor c=db.rawQuery("SELECT id,name,form,source_id,required_tokens,excluded_tokens FROM Medicine ORDER BY name",null)){
                while(c.moveToNext()){
                    String id=c.getString(0);List<String> aliases=new ArrayList<>();List<Ingredient> ingredients=new ArrayList<>();
                    try(Cursor a=db.rawQuery("SELECT value FROM Alias WHERE medicine_id=?",new String[]{id})){while(a.moveToNext())aliases.add(a.getString(0));}
                    try(Cursor i=db.rawQuery("SELECT i.id,i.name,mi.strength,mi.unit FROM MedicineIngredient mi JOIN Ingredient i ON i.id=mi.ingredient_id WHERE mi.medicine_id=?",new String[]{id})){while(i.moveToNext())ingredients.add(new Ingredient(i.getString(0),i.getString(1),i.getString(2),i.getString(3)));}
                    medicines.add(new Medicine(id,c.getString(1),c.getString(2),c.getString(3),aliases,strings(c.getString(4)),strings(c.getString(5)),ingredients));
                }
            }
            try(Cursor c=db.rawQuery("SELECT id,ingredient_a,ingredient_b,severity,explanation,source_id FROM Interaction",null)){while(c.moveToNext())interactions.add(new Interaction(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)));}
        }catch(Exception e){db.close();throw e;}
    }
    private static List<String> strings(String json)throws Exception{JSONArray a=new JSONArray(json);List<String> out=new ArrayList<>();for(int i=0;i<a.length();i++)out.add(a.getString(i));return out;}
    public String source(String id){try(Cursor c=db.rawQuery("SELECT name,record_id,url,version,checked_at,status FROM Source WHERE id=?",new String[]{id})){if(!c.moveToFirst())return "Source unavailable";return c.getString(0)+"\n"+c.getString(1)+"\n"+c.getString(3)+"\nChecked "+c.getString(4)+"\nSource checked; clinical review pending.\n\n"+c.getString(2);}}
    private static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);return b.toByteArray();}
    private static String hash(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    public void close(){db.close();}
}
