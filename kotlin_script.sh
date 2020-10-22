: "${java_cmd:="java"}"
: "${script_file:="$0"}"

: "${K2_REPO:=https://repo1.maven.org/maven2}"

tmp_d=
trap '[ x"$tmp_f" = x ] || rm -f "$tmp_f"; [ x"$tmp_d" = x ] || rm -Rf "$tmp_d"' EXIT

kotlin_script_flags=
case "$-" in
*x*)
  kotlin_script_flags="$kotlin_script_flags -x"
  ;;
esac

if [ -t 2 ]; then
  kotlin_script_flags="$kotlin_script_flags -P"
fi

if [ x"${dgst_cmd:="$sha256_cmd"}" = x ] && \
   ! dgst_cmd="$(command -v sha256sum)" && \
   ! dgst_cmd="$(command -v openssl) dgst -sha256 -r"; then
  echo "error: no sha256 tool available" >&2
  exit 1
fi

if [ "${script_dir:="${script_file%/*}"}" = "$script_file" ]; then
  script_dir=.
fi
script_name="${script_file##*/}"

cache_dir="${K2_LOCAL_REPO:-"$HOME"/.m2/repository}"/org/cikit/kotlin_script_cache/@kotlin_script_jar_ver@

if [ -d "$cache_dir" ]; then
  # lookup script metadata by script dgst
  if ! script_dgst_out="$(${dgst_cmd} < "$script_file")"; then
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
          if ! [ -r "${K2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"${line#*=}" ]; then
            check_classpath=fail
            break
          fi
          ;;
        '///INC='*)
          inc="${line#///INC=}"
          if ! inc_dgst_out="$(cd "$script_dir" && ${dgst_cmd} < "$inc")"; then
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
          target_dgst_out="$(${dgst_cmd} << __EOF__
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
            if ! [ x"$tmp_f" = x ]; then
              rm -f "$fmp_f"
              tmp_f=
            fi

            if ! [ x"$tmp_d" = x ]; then
              rm -Rf "$tmp_d"
              tmp_d=
            fi

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

if [ x"${fetch_cmd:=}" = x ]; then
  if fetch="$(command -v fetch) --no-verify-peer -aAqo" || \
     fetch="$(command -v wget) --no-check-certificate -qO" || \
     fetch="$(command -v curl) -kfLSso"; then
    fetch_cmd="$fetch"
  fi
fi

do_fetch()
{
  dest="$1"
  p="$2"
  dgst="$3"

  if [ -r "$K2_LOCAL_MIRROR"/"$p" ]; then
    cp -f "$K2_LOCAL_MIRROR"/"$p" "$dest"
    case "$($dgst_cmd < "$dest")" in
    "$dgst "*)
      return
      ;;
    esac
  fi
  if [ -t 2 ]; then
    echo "fetching $K2_REPO/$p" >&2
  fi
  if ! $fetch_cmd "$dest" "$K2_REPO/$p"; then
    echo "error: failed to fetch $K2_REPO/$p" >&2
    exit 1
  fi
  case "$($dgst_cmd < "$dest")" in
  "$dgst "*)
    return
    ;;
  esac
  echo "error: failed to validate $K2_REPO/$p" >&2
  exit 1
}

tmp_d="$(mktemp -d)" || exit 1

kotlin_script_jar=org/cikit/kotlin_script/@kotlin_script_jar_ver@/kotlin_script-@kotlin_script_jar_ver@.jar
kotlin_stdlib_jar=org/jetbrains/kotlin/kotlin-stdlib/@kotlin_stdlib_ver@/kotlin-stdlib-@kotlin_stdlib_ver@.jar

if [ -r "${K2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"$kotlin_script_jar" ] && \
   [ -r "${K2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"$kotlin_stdlib_jar" ]; then
  kotlin_script_jar="${K2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"$kotlin_script_jar"
  kotlin_stdlib_jar="${K2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"$kotlin_stdlib_jar"
else
  if [ x"$fetch_cmd" = x ]; then
    echo "error: no fetch tool available" >&2
    exit 1
  fi
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
                         -M "$tmp_d"/script.metadata \
                         "$script_file")"; then
  exit 1
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
done < "$tmp_d"/script.metadata

if ! [ x"$tmp_f" = x ]; then
  rm -f "$fmp_f"
  tmp_f=
fi

if ! [ x"$tmp_d" = x ]; then
  rm -Rf "$tmp_d"
  tmp_d=
fi

exec $java_cmd \
       -Dkotlin_script.name="$script_file" \
       -Dkotlin_script.flags="$kotlin_script_flags" \
       -jar "$target_repo"/"$target_jar" "$@"

exit 2
