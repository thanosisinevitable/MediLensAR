import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;
/** Syntax-only check of Android sources, explicitly not an Android SDK compilation. */
class ParseJava {
 public static void main(String[] args)throws Exception{
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
  try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,null);var paths=Files.walk(Path.of("app/src/main/java"))){
   var files=paths.filter(p->p.toString().endsWith(".java")).map(Path::toFile).toList();
   JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,manager.getJavaFileObjectsFromFiles(files));
   task.parse();long errors=diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).count();
   diagnostics.getDiagnostics().forEach(System.out::println);System.out.println("Java syntax: "+files.size()+" files, "+errors+" errors. Android API linking not checked.");if(errors>0)System.exit(1);
  }
 }
}
