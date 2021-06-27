: "${java_cmd:="java"}"
: "${script_file:="$0"}"

: "${M2_LOCAL_REPO:="$HOME"/.m2/repository}"
: "${M2_CENTRAL_REPO:=https://repo1.maven.org/maven2}"

run_dgst_cmd()
{
  if [ x"$dgst_cmd" = x ] &&\
     ! dgst_cmd="$(command -v openssl) dgst -sha256 -r" &&\
     ! dgst_cmd="$(command -v sha256sum)"; then
    echo "error: no sha256 tool available" >&2
    exit 1
  fi
  run_dgst_cmd()
  {
    ${dgst_cmd} "$@"
  }
  run_dgst_cmd "$@"
}

run_fetch_cmd()
{
  if [ x"$fetch_cmd" = x ] &&\
     ! fetch_cmd="$(command -v curl) -kfLSso" && \
     ! fetch_cmd="$(command -v fetch) --no-verify-peer -aAqo" && \
     ! fetch_cmd="$(command -v wget) --no-check-certificate -qO"; then
    echo "error: no fetch tool available" >&2
    exit 1
  fi
  run_fetch_cmd()
  {
    ${fetch_cmd} "$@"
  }
  run_fetch_cmd "$@"
}

kotlin_script_flags=
case "$-" in
*x*)
  kotlin_script_flags="$kotlin_script_flags -x"
  ;;
esac

if [ -t 2 ]; then
  kotlin_script_flags="$kotlin_script_flags -P"
fi

if [ "${script_dir:="${script_file%/*}"}" = "$script_file" ]; then
  script_dir=.
fi
script_name="${script_file##*/}"

cache_dir="$M2_LOCAL_REPO"/org/cikit/kotlin_script_cache/@kotlin_script_jar_ver@

case "$kotlin_script_sh" in
"$M2_LOCAL_REPO"*)
  force_install=no
  ;;
*)
  force_install=yes
  ;;
esac

if [ "$force_install" = no ] && [ -d "$cache_dir" ]; then
  # lookup script metadata by script dgst
  if ! script_dgst_out="$(run_dgst_cmd < "$script_file")"; then
    echo "error calculating dgst of $script_file: $dgst_cmd terminated abnormally" >&2
    exit 1
  fi
  script_dgst="${script_dgst_out%%[^0-9a-f]*}"
  case "$script_dgst" in
  ????????????????????????????????????????????????????????????????)
    md_cache=kotlin_script_cache-@kotlin_script_jar_ver@-sha256="$script_dgst".metadata
    if [ -r "$cache_dir"/"$md_cache" ]; then
      # read script metadata from cache
      check_classpath=
      check_inc=
      while read -r line; do
        case "$line" in
        '///DEP='* | '///RDEP='*)
          if ! [ -r "$M2_LOCAL_REPO"/"${line#*=}" ]; then
            check_classpath=fail
            break
          fi
          ;;
        '///INC='*)
          inc="${line#///INC=}"
          if ! inc_dgst_out="$(cd "$script_dir" && run_dgst_cmd < "$inc")"; then
            check_inc=fail
            break
          fi
          inc_dgst="${inc_dgst_out%%[^0-9a-f]*}"
          case "$inc_dgst" in
          ????????????????????????????????????????????????????????????????)
            check_inc="$check_inc
sha256=$inc_dgst $inc"
            ;;
          *)
            check_inc=fail
            break
            ;;
          esac
          ;;
        esac
      done < "$cache_dir"/"$md_cache"
      if [ x"$check_classpath" = x ] && [ "$check_inc" != "fail" ]; then
        if [ x"$check_inc" = x ]; then
          target_dgst="$script_dgst"
        else
          target_dgst_out="$(run_dgst_cmd << __EOF__
sha256=$script_dgst $script_name$check_inc
__EOF__
)" || exit 1
          target_dgst="${target_dgst_out%%[^0-9a-f]*}"
        fi
        case "$target_dgst" in
        ????????????????????????????????????????????????????????????????)
          # lookup jar by script+inc dgst
          target=kotlin_script_cache-@kotlin_script_jar_ver@-sha256="$target_dgst".jar
          if [ -r "$cache_dir"/"$target" ]; then
            exec $java_cmd \
                   -Dkotlin_script.name="$script_file" \
                   -Dkotlin_script.flags="$kotlin_script_flags" \
                   -jar "$cache_dir"/"$target" "$@"
            exit 2
          fi
          ;;
        esac
      fi
    fi
    ;;
  esac
fi

do_fetch()
{
  dest="$1"
  p="$2"
  dgst="$3"

  if [ -r "$M2_LOCAL_REPO"/"$p" ]; then
    cp -f "$M2_LOCAL_REPO"/"$p" "$dest"
    return
  fi
  if [ -r "$M2_LOCAL_MIRROR"/"$p" ]; then
    cp -f "$M2_LOCAL_MIRROR"/"$p" "$dest"
    case "$(run_dgst_cmd < "$dest")" in
    "$dgst "*)
      return
      ;;
    esac
  fi
  if [ -t 2 ]; then
    echo "fetching $M2_CENTRAL_REPO/$p" >&2
  fi
  if ! run_fetch_cmd "$dest" "$M2_CENTRAL_REPO/$p"; then
    echo "error: failed to fetch $M2_CENTRAL_REPO/$p" >&2
    exit 1
  fi
  case "$(run_dgst_cmd < "$dest")" in
  "$dgst "*)
    return
    ;;
  esac
  echo "error: failed to verify $M2_CENTRAL_REPO/$p" >&2
  exit 1
}

tmp_d=
script_metadata="$(mktemp)" || exit 1
trap '[ x"$tmp_d" = x ] || rm -Rf "$tmp_d"; [ x"$script_metadata" = x ] || rm -f "$script_metadata"' EXIT

kotlin_script_jar=org/cikit/kotlin_script/@kotlin_script_jar_ver@/kotlin_script-@kotlin_script_jar_ver@.jar
kotlin_stdlib_jar=org/jetbrains/kotlin/kotlin-stdlib/@kotlin_stdlib_ver@/kotlin-stdlib-@kotlin_stdlib_ver@.jar

if [ -r "$M2_LOCAL_REPO"/"$kotlin_script_jar" ] && \
   [ -r "$M2_LOCAL_REPO"/"$kotlin_stdlib_jar" ]; then
  kotlin_script_jar="$M2_LOCAL_REPO"/"$kotlin_script_jar"
  kotlin_stdlib_jar="$M2_LOCAL_REPO"/"$kotlin_stdlib_jar"
else
  tmp_d="$(mktemp -d)" || exit 1
  mkdir -p "$tmp_d"/"${kotlin_script_jar%/*}" "$tmp_d"/"${kotlin_stdlib_jar%/*}"
  do_fetch "$tmp_d"/"$kotlin_script_jar" "$kotlin_script_jar" @kotlin_script_jar_dgst@
  do_fetch "$tmp_d"/"$kotlin_stdlib_jar" "$kotlin_stdlib_jar" @kotlin_stdlib_dgst@
  kotlin_script_jar="$tmp_d"/"$kotlin_script_jar"
  kotlin_stdlib_jar="$tmp_d"/"$kotlin_stdlib_jar"
fi

if ! target="$($java_cmd -jar "$kotlin_script_jar" \
                         ${kotlin_script_flags} \
                         --install-kotlin-script-sh="$kotlin_script_sh" \
                         --install-kotlin-script-jar="$kotlin_script_jar" \
                         --install-kotlin-stdlib-jar="$kotlin_stdlib_jar" \
                         -M "$script_metadata" \
                         "$script_file")"; then
  exit 1
fi

if ! [ x"$tmp_d" = x ]; then
  rm -Rf "$tmp_d"
  tmp_d=
fi

target_repo=
target_jar=
while read -r line; do
  case "$line" in
  ///REPO=*)
    target_repo="${line#///REPO=}"
    ;;
  ///JAR_CACHE_PATH=*)
    target_jar="${line#///JAR_CACHE_PATH=}"
    ;;
  esac
done < "$script_metadata"

rm -f "$script_metadata"
script_metadata=

exec $java_cmd \
       -Dkotlin_script.name="$script_file" \
       -Dkotlin_script.flags="$kotlin_script_flags" \
       -jar "$target_repo"/"$target_jar" "$@"

exit 2
