#!/bin/sh

: ${REPO:=https://repo1.maven.org/maven2}
: ${FORCE_DOWNLOAD:=no}

SRC=src/installer.kt
INC=src/KotlinScript.kt
DST=work/kotlin_script_installer.jar

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
  echo -n "/$2/$3/$2-$3${4:-.jar}"
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

if FETCH=$(which fetch 2>/dev/null); then
download()
{
  local dst="$1"
  local src="$2"
  exec fetch -o "$dst" "$src"
}
else
download()
{
  local dst="$1"
  local src="$2"
  exec curl -fo "$dst" "$src"
}
fi

mkdir -p work/lib

CP=

while read line
do
  case "$line" in
  '///COMPILER='*)
    path=$(deppath "${line#///COMPILER=}")
    sha256="${line##*sha256=}"
    sha256="${sha256%[^0-9a-fA-F]}"
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
         download "$target"~ "$REPO"/"$path"
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
      echo "checksum mismatch: $target ($sha256)" >&2
      exit 2
    esac
    CP="$CP""$PATH_SEPARATOR"$(cygpath "$target")
    ;;
  esac
done < "$SRC"

CP="${CP#$PATH_SEPARATOR}"

if ! [ "$DST" -nt "$SRC" ]; then
  (
    set -x
    exec "$JAVA_HOME"/bin/java \
      -Djava.awt.headless=true \
      -cp "$CP" \
      org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -kotlin-home work \
      -no-reflect \
      -include-runtime \
      -d "$DST" \
      "$SRC" $INC
  )
fi

(
  set -x
  "$JAVA_HOME"/bin/java -jar "$DST"
)
