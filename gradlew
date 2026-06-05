#!/bin/sh
#
# Gradle wrapper script (minimal version that delegates to the jar)
#

# Resolve the script's own directory
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# JVM options
DEFAULT_JVM_OPTS="-Xmx64m" 
JAVA_EXE="java"

exec "$JAVA_EXE" $DEFAULT_JVM_OPTS \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
