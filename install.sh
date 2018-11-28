#!/bin/sh

: ${REPO:=https://repo1.maven.org/maven2}
: ${FORCE_DOWNLOAD:=no}

SRC=src/kotlin_script.kt

set -e

if [ -z "$JAVA_HOME" ]
then
  JAVA_CMD=$(which java)
  JAVA_BINDIR=$(dirname "$JAVA_CMD")
  JAVA_HOME=$(dirname "$JAVA_BINDIR")
fi

deppath()
{
  local IFS=:
  set -- $1
  echo -n "$1" | tr '.' '/'
  echo -n "/$2/$3/$2-$3.${4:-jar}"
}

if CYGPATH=$(which cygpath 2>/dev/null); then
PATH_SEPARATOR=";"
cygpath()
{
  "$CYGPATH" -m "$1"
}
else
PATH_SEPARATOR=":"
cygpath()
{
  echo "$1"
}
fi

mkdir -p work

CP=

while read line
do
  case "$line" in
  '///COMPILER='*)
    path=$(deppath "${line#///COMPILER=}")
    sha256="${line##*sha256=}"
    basename=$(basename "$path")
    target=work/lib/"${basename%-[0-9]*.jar}".jar
    if [ "$FORCE_DOWNLOAD" = "yes" ] || ! [ -e "$target" ]; then
      if [ "$FORCE_DOWNLOAD" != "yes" ] && [ -e ~/.m2/repository/"$path" ]; then
        (
         set -x
         exec cp -f ~/.m2/repository/"$path" "$target"~
        )
      else
        (
         set -x
         exec curl -fo "$target"~ "$REPO"/"$path"
        )
      fi
      mv -f "$target"~ "$target"
    fi
    CHK=$(openssl dgst -sha256 "$target")
    case "$CHK" in
    *"$sha256"*)
      true
      ;;
    *)
      echo "checksum mismatch!" >&2
      exit 2
    esac
    CP="$CP""$PATH_SEPARATOR"$(cygpath "$target")
    ;;
  esac
done < "$SRC"

if ! [ work/kotlin_script.jar -nt "$SRC" ]; then
  (
    set -x
    exec "$JAVA_HOME"/bin/java -cp work"$CP" \
      org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -kotlin-home work \
      -include-runtime \
      -d work/kotlin_script.jar \
      "$SRC"
  )
fi

(
  set -x
  exec "$JAVA_HOME"/bin/java -cp work/kotlin_script.jar kotlin_script \
    --install \
    "$SRC"
)
