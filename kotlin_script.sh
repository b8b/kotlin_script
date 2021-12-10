#!/bin/sh

: "${script_file:="$0"}"

export M2_LOCAL_REPO="${M2_LOCAL_REPO:-"$HOME"/.m2/repository}"
export M2_CENTRAL_REPO="${M2_CENTRAL_REPO:-https://repo1.maven.org/maven2}"

export KOTLIN_VERSION="@kotlin_stdlib_ver@"
export KOTLIN_STDLIB_SHA256="@kotlin_stdlib_dgst@"

export KOTLIN_SCRIPT_VERSION="@kotlin_script_jar_ver@"
export KOTLIN_SCRIPT_SHA256="@kotlin_script_jar_dgst@"

kotlin_script_flags=
case "$-" in
*x*)
  kotlin_script_flags="$kotlin_script_flags -x"
  ;;
esac

if [ -t 2 ]; then
  kotlin_script_flags="$kotlin_script_flags -P"
fi

if [ -z "$kotlin_script_sh" ]; then
  # called directly
  exec ${java_cmd:-java} \
         -Dkotlin_script.name="$1" \
         -Dkotlin_script.flags="$kotlin_script_flags" \
         -jar "$script_file" "$@"
  exit 2
fi

exec ${java_cmd:-java} \
       -Dkotlin_script.sh="$kotlin_script_sh" \
       -Dkotlin_script.name="$script_file" \
       -Dkotlin_script.flags="$kotlin_script_flags" \
       -jar "$kotlin_script_sh" "$script_file" "$@"

exit 2
