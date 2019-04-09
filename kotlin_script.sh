: ${ks_home:="$HOME/.kotlin_script"}
: ${java_cmd:="java"}
: ${sha256_cmd:="openssl dgst -sha256 -r"}
: ${script_file:="$0"}

script_dir="$(dirname "$script_file")"
script_name="${script_file##*/}"
script_sha256="$(${sha256_cmd} < "$script_file")"
script_sha256="${script_sha256%% *}"
md_cache_dir="$ks_home"/cache-@ks_jar_ver@/"${script_sha256%??????????????????????????????????????????????????????????????}"
md_cache="$md_cache_dir"/"${script_sha256#??}".metadata

parse_script_metadata()
{
  while read -r line; do
    case "$line" in
    '///INC='*)
      set -- "$@" "${line#///INC=}"
      ;;
    esac
  done < "$md_cache"
  if [ "$#" -gt 0 ]; then
    chk="$script_sha256 *$script_name
$(cd "$script_dir" && ${sha256_cmd} "$@")" || exit 1
  else
    chk="$script_sha256 *$script_name"
  fi
  target_sha256="$(${sha256_cmd} << __EOF__
$chk

__EOF__
)" || exit 1
  target_sha256="${target_sha256%% *}"
  target="$ks_home"/cache-@ks_jar_ver@/"${target_sha256%??????????????????????????????????????????????????????????????}"/"${target_sha256#??}".jar
}

if [ -e "$md_cache" ]; then
  parse_script_metadata
  if [ -e "$target" ]; then
    #TODO check if all dependencies are present
    exec ${java_cmd} -jar "$target" "$@"
    exit 2
  fi
fi

: ${repo:="https://repo1.maven.org/maven2"}
: ${local_repo:="$HOME/.m2/repository"}

if [ x"$fetch_cmd" = x ] && \
    ! fetch_s="$(command -v fetch) -aAqo" && \
    ! fetch_s="$(command -v curl) -fSso"; then
  fetch_cmd=""
else
  fetch_cmd="$fetch_s"
fi

do_fetch()
{
  dest="$1"
  artifact="$2"
  sha256="$3"

  if [ -e "$local_repo"/"$artifact" ]; then
    tmp_f=$(mktemp "$dest"~XXXXXXXXXXXXXXXX) || exit 1
    cp -f "$local_repo"/"$artifact" "$tmp_f" || exit 1
    case "$(${sha256_cmd} < "$tmp_f")" in
    "$sha256 "*)
      mv -f "$tmp_f" "$dest" || exit 1
      return
      ;;
    esac
    echo "warning: failed to validate $local_repo/$artifact" >&2
    if [ x"$fetch_cmd" = x ]; then
      echo "error: no fetch tool available" >&2
      exit 1
    fi
    if ! ${fetch_cmd} "$tmp_f" "$repo"/"$artifact"; then
      echo "error: failed to fetch $repo/$artifact" >&2
      exit 1
    fi
    case "$(${sha256_cmd} < "$tmp_f")" in
    "$sha256 "*)
      mv -f "$tmp_f" "$dest" || exit 1
      return
      ;;
    esac
  else
    tmp_f=$(mktemp "$local_repo"/"$artifact"~XXXXXXXXXXXXXXXX) || exit 1
    if [ x"$fetch_cmd" = x ]; then
      echo "error: no fetch tool available" >&2
      exit 1
    fi
    if ! ${fetch_cmd} "$tmp_f" "$repo"/"$artifact"; then
      echo "error: failed to fetch $repo/$artifact" >&2
      exit 1
    fi
    case "$(${sha256_cmd} < "$tmp_f")" in
    "$sha256 "*)
      cp -f "$tmp_f" "$dest"~"${tmp_f##*/}" || exit 1
      if ! mv -f "$dest"~"${tmp_f##*/}" "$dest"; then
        rm -f "$dest"~"${tmp_f##*/}"
        mv -f "$tmp_f" "$local_repo"/"$artifact"
        exit 1
      else
        mv -f "$tmp_f" "$local_repo"/"$artifact"
      fi
      return
      ;;
    esac
  fi
  echo "error: failed to validate $tmp_f" >&2
  exit 1
}

trap '[ x"$tmp_f" = x ] || rm -f "$tmp_f"'

if ! [ -e "$ks_home"/kotlin-compiler-"@stdlib_ver@"/kotlinc/lib/kotlin-stdlib.jar ]; then
  mkdir -p "$ks_home"/kotlin-compiler-"@stdlib_ver@"/kotlinc/lib \
      "$local_repo"/org/jetbrains/kotlin/kotlin-stdlib/"@stdlib_ver@"
  do_fetch "$ks_home"/kotlin-compiler-"@stdlib_ver@"/kotlinc/lib/kotlin-stdlib.jar \
           org/jetbrains/kotlin/kotlin-stdlib/"@stdlib_ver@"/kotlin-stdlib-"@stdlib_ver@".jar \
           "@stdlib_sha256@"
fi

mkdir -p "$ks_home"/cache-@ks_jar_ver@/work

if ! [ -e "$ks_home"/kotlin_script-"@ks_jar_ver@".jar ]; then
  mkdir -p "$local_repo"/org/cikit/kotlin_script/kotlin_script/"@ks_jar_ver@"
  do_fetch "$ks_home"/kotlin_script-"@ks_jar_ver@".jar \
           org/cikit/kotlin_script/kotlin_script/"@ks_jar_ver@"/kotlin_script-"@ks_jar_ver@".jar \
           "@ks_jar_sha256@"
fi

if ! tmp_f="$(mktemp "$ks_home"/cache-@ks_jar_ver@/work/XXXXXXXXXXXXXXXX)"; then
  exit 1
fi

case "$-" in
*x*)
  kotlin_script_flags="$kotlin_script_flags -x"
  ;;
esac

if ! ${java_cmd} -Dmaven.repo.url="$repo" \
                 -Dmaven.repo.local="$local_repo" \
                 -Dkotlin_script.home="$ks_home" \
                 -jar "$ks_home"/kotlin_script-"@ks_jar_ver@".jar \
                 ${kotlin_script_flags} \
                 -M "$tmp_f" \
                 -d "$tmp_f".jar \
                 "$script_file"; then
  rm -f "$tmp_f" "$tmp_f".jar
  exit 1
fi

chk=
inc=
while read -r line; do
  case "$line" in
  '///INC='*)
    inc="${line#///INC=}"
    ;;
  '///CHK=sha256='*)
    sha256="${line#///CHK=sha256=}"
    if [ x"$inc" = x ]; then
      script_sha256="$sha256"
      md_cache_dir="$ks_home"/cache-@ks_jar_ver@/"${script_sha256%??????????????????????????????????????????????????????????????}"
      md_cache="$md_cache_dir"/"${script_sha256#??}".metadata
      chk="${chk}$sha256 *$script_name"$'\n'
    else
      chk="${chk}$sha256 *$inc"$'\n'
    fi
    ;;
  esac
done < "$tmp_f"

target_sha256="$(${sha256_cmd} << __EOF__
$chk
__EOF__
)" || exit 1
target_sha256="${target_sha256%% *}"
target_dir="$ks_home"/cache-@ks_jar_ver@/"${target_sha256%??????????????????????????????????????????????????????????????}"
target="$target_dir"/"${target_sha256#??}".jar

if ! mkdir -p "$target_dir" "$md_cache_dir"; then
  rm -f "$tmp_f" "$tmp_f".jar
  exit 1
fi

if ! mv -f "$tmp_f" "$md_cache"; then
  rm -f "$tmp_f" "$tmp_f".jar
  exit 1
fi

if ! mv -f "$tmp_f".jar "$target"; then
  rm -f "$tmp_f" "$tmp_f".jar
  exit 1
fi

exec ${java_cmd} -jar "$target" "$@"
exit 2
