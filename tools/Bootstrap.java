import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
/** Fetches only the official Gradle wrapper JAR and verifies its published SHA-256. */
class Bootstrap {
    static final String SHA="2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046";
    public static void main(String[] args)throws Exception{
        Path file=Path.of("gradle/wrapper/gradle-wrapper.jar");
        if(Files.exists(file)&&hash(file).equals(SHA)){System.out.println("Official Gradle wrapper verified.");return;}
        System.out.println("Downloading the official Gradle 8.11.1 wrapper (first setup only)…");
        Path tmp=file.resolveSibling("wrapper.download");
        try{
            URLConnection c=URI.create("https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar").toURL().openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);
            try(InputStream in=c.getInputStream()){Files.copy(in,tmp,StandardCopyOption.REPLACE_EXISTING);}
            if(!hash(tmp).equals(SHA))throw new SecurityException("Gradle wrapper checksum mismatch; refusing execution");
            Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Official Gradle wrapper verified.");
        }finally{Files.deleteIfExists(tmp);}
    }
    static String hash(Path p)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
