#!/bin/sh

set -e

if [ -z "$JAVA_HOME" ]
then
  JAVA_CMD=$(which java)
  JAVA_BINDIR=$(dirname "$JAVA_CMD")
  JAVA_HOME=$(dirname "$JAVA_BINDIR")
fi

KOTLIN_HOME=../kotlinc

if [ -x "$KOTLIN_HOME"/bin/kotlinc ]
then
  KOTLINC_BIN="$KOTLIN_HOME"/bin/kotlinc
else
  KOTLINC_BIN="$KOTLIN_HOME"/bin/kotlinc.bat
  JAVA_HOME=$(cygpath -md "$JAVA_HOME")
  KOTLIN_HOME=$(cygpath -md "$KOTLIN_HOME")
fi

KOTLINC_ARGS="-include-runtime -d kotlin_script.jar src/kotlin_script.kt"

set -x

"$KOTLINC_BIN" -jdk-home "$JAVA_HOME" ${KOTLINC_ARGS}

"$JAVA_HOME"/bin/java -jar kotlin_script.jar --install src/kotlin_script.kt
