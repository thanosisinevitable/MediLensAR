"""Offline verification. Does not mislabel source parsing as Android compilation."""
from pathlib import Path
import os,subprocess,sys,time,json
ROOT=Path(__file__).resolve().parents[1]
def run(args):
    result=subprocess.run(args,cwd=ROOT,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    print(result.stdout,end='');return result
if __name__=='__main__':
    java=str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')) if 'JAVA_HOME' in os.environ else 'java'
    out=ROOT/'.local/classes';out.mkdir(parents=True,exist_ok=True)
    sources=list((ROOT/'app/src/main/java/org/medilens/core').glob('*.java'))+[ROOT/'tests/CoreTests.java']
    commands=[[java,'-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(out)]+list(map(str,sources)),[java,'-cp',str(out),'CoreTests'],[java,'tools/ParseJava.java'],[sys.executable,'-m','unittest','discover','-s','tests','-p','test_*.py','-v']]
    logs=[];start=time.perf_counter();success=True
    for command in commands:
        result=run(command);logs.append('$ '+' '.join(command)+'\n'+result.stdout);success &= result.returncode==0
        if result.returncode:break
    (ROOT/'evaluation/local-verification.txt').write_text('\n'.join(logs))
    (ROOT/'evaluation/local-verification.json').write_text(json.dumps(dict(passed=success,host='local Linux Java17/Python verification',elapsedSeconds=round(time.perf_counter()-start,3),androidBuild='NOT VERIFIED',phoneEvaluation='NOT YET MEASURED'),indent=2)+'\n')
    sys.exit(0 if success else 1)
