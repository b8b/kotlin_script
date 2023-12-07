#!/bin/sh

: "${script_file:="$0"}"

if [ -n "$M2_LOCAL_REPO" ]; then
  export M2_LOCAL_REPO="$M2_LOCAL_REPO"
fi

if [ -n "$M2_LOCAL_MIRROR" ]; then
  export M2_LOCAL_MIRROR="$M2_LOCAL_MIRROR"
fi

if [ -n "$M2_CENTRAL_REPO" ]; then
  export M2_CENTRAL_REPO="$M2_CENTRAL_REPO"
fi

case "$-" in
*x*)
  KOTLIN_SCRIPT_FLAGS="-x $KOTLIN_SCRIPT_FLAGS"
  ;;
esac

if [ -t 2 ]; then
  KOTLIN_SCRIPT_FLAGS="-P $KOTLIN_SCRIPT_FLAGS"
fi

export KOTLIN_SCRIPT_FLAGS="$KOTLIN_SCRIPT_FLAGS"

case "$kotlin_script_sh" in
"${M2_LOCAL_REPO:-"$HOME"/.m2/repository}"/*)
  # called directly
  exec ${java_cmd:-java} -jar "$kotlin_script_sh" "$script_file" "$@"
  exit 2
esac

# running with temporary kotlin_script_sh
trap 'rm -f "$kotlin_script_sh"' EXIT
${java_cmd:-java} -jar "$kotlin_script_sh" "$script_file" "$@"
exit "$?"
