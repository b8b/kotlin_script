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

export KOTLIN_VERSION="@kotlin_stdlib_ver@"
export KOTLIN_STDLIB_SHA256="@kotlin_stdlib_dgst@"

export KOTLIN_SCRIPT_VERSION="@kotlin_script_jar_ver@"
export KOTLIN_SCRIPT_SHA256="@kotlin_script_jar_dgst@"

case "$-" in
*x*)
  KOTLIN_SCRIPT_FLAGS="-x $KOTLIN_SCRIPT_FLAGS"
  ;;
esac

if [ -t 2 ]; then
  KOTLIN_SCRIPT_FLAGS="-P $KOTLIN_SCRIPT_FLAGS"
fi

export KOTLIN_SCRIPT_FLAGS="$KOTLIN_SCRIPT_FLAGS"

if [ -z "$kotlin_script_sh" ]; then
  # called directly
  exec ${java_cmd:-java} \
         -Dkotlin_script.name="$1" \
         -Dkotlin_script.flags="$KOTLIN_SCRIPT_FLAGS" \
         -jar "$script_file" "$@"
  exit 2
fi

if [ -z "$M2_LOCAL_REPO" ]; then
  if [ -d "$HOME" ] && [ -O "$HOME" ]; then
    install_to_repo="$HOME"/.m2/repository
  else
    # running with temporary kotlin_script_sh
    trap 'rm -f "$kotlin_script_sh"' EXIT
    ${java_cmd:-java} \
           -Dkotlin_script.name="$script_file" \
           -Dkotlin_script.flags="$KOTLIN_SCRIPT_FLAGS" \
           -jar "$kotlin_script_sh" "$script_file" "$@"
    exit "$?"
  fi
else
  install_to_repo="$M2_LOCAL_REPO"
fi

if ! [ "$kotlin_script_sh" -ef "$install_to_repo"/org/cikit/kotlin_script/"$KOTLIN_SCRIPT_VERSION"/kotlin_script-"$KOTLIN_SCRIPT_VERSION".sh ]; then
  mkdir -p "$install_to_repo"/org/cikit/kotlin_script/"$KOTLIN_SCRIPT_VERSION"
  if ! cp "$kotlin_script_sh" "$install_to_repo"/org/cikit/kotlin_script/"$KOTLIN_SCRIPT_VERSION"/kotlin_script-"$KOTLIN_SCRIPT_VERSION".sh; then
    # running with temporary kotlin_script_sh
    trap 'rm -f "$kotlin_script_sh"' EXIT
    ${java_cmd:-java} \
           -Dkotlin_script.name="$script_file" \
           -Dkotlin_script.flags="$KOTLIN_SCRIPT_FLAGS" \
           -jar "$kotlin_script_sh" "$script_file" "$@"
    exit "$?"
  fi
fi

exec ${java_cmd:-java} \
       -Dkotlin_script.name="$script_file" \
       -Dkotlin_script.flags="$KOTLIN_SCRIPT_FLAGS" \
       -jar "$kotlin_script_sh" "$script_file" "$@"

exit 2
