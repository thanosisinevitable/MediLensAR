#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ -n "${JAVA_HOME:-}" ]; then
  MEDILENS_JAVA="$JAVA_HOME/bin/java"
elif [ -x '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java' ]; then
  MEDILENS_JAVA='/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java'
else
  MEDILENS_JAVA=java
fi
"$MEDILENS_JAVA" tools/Bootstrap.java
exec "$MEDILENS_JAVA" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
