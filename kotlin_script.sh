: ${ks_home:="$HOME/.kotlin_script"}
: ${java_cmd:="java"}
: ${sha256_cmd:="openssl dgst -sha256 -r"}
: ${script_file:="$0"}

script_dir="$(dirname "$script_file")"
script_name="${script_file##*/}"
script_sha256="$(${sha256_cmd} < "$script_file")"
script_sha256="${script_sha256%% *}"
script_cache_dir="$ks_home"/cache-@ks_jar_ver@/"${script_sha256%??????????????????????????????????????????????????????????????}"
script_cache="$script_cache_dir"/"${script_sha256#??}"

parse_script_metadata()
{
  while read line; do
    case "$line" in
    '///INC='*)
      set -- "$@" "${line#///INC=}"
      ;;
    esac
  done < "$script_cache".metadata
  if [ "$#" -gt 0 ]; then
    chk="$script_sha256 *$script_name"$'\n'"$(cd "$script_dir" && ${sha256_cmd} "$@")" || exit 1
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

if [ -e "$script_cache".metadata ]; then
  parse_script_metadata
  if [ -e "$target" ]; then
    exec ${java_cmd} -jar "$target" "$@"
    exit 2
  fi
fi

do_fetch()
{
  dest="$1"
  artifact="$2"
  sha256="$3"
  retry=1
  if [ -e "$local_repo"/"$artifact" ]; then
    cp -f "$local_repo"/"$artifact" "$dest"~1
  fi
  while true; do
    if [ -e "$dest"~1 ]; then
      case "$(${sha256_cmd} < "$dest"~1)" in
      "$sha256 "*)
        mv -f "$dest"~1 "$dest"
        return
        ;;
      esac
    fi
    if ! [ "$retry" -gt 0 ]; then
      break
    fi
    retry=$(($retry - 1))
    if ! ${fetch_cmd} "$repo"/"$artifact" >"$dest"~1 2>"$dest"~2; then
      cat "$dest"~2 >&2
      rm -f "$dest"~2
      echo "error: failed to fetch $repo/$artifact" >&2
      exit 1
    fi
    rm -f "$dest"~2
  done
  echo "error: failed to validate $dest~1" >&2
  exit 1
}

if ! [ -e "$ks_home"/kotlin-compiler-"@stdlib_ver@"/kotlinc/lib/kotlin-stdlib.jar ]; then
  : ${repo:="https://repo1.maven.org/maven2"}
  : ${local_repo:="$HOME/.m2/repository"}
  : ${fetch_cmd:="$(command -v fetch || command -v curl) -o-"}

  mkdir -p "$ks_home"/kotlin-compiler-"@stdlib_ver@"/kotlinc/lib/kotlin-stdlib.jar
  do_fetch "$ks_home"/kotlin-compiler-"@stdlib_ver@"/kotlinc/lib/kotlin-stdlib.jar \
           org/jetbrains/kotlin/kotlin-stdlib/"@stdlib_ver@"/kotlin-stdlib-"@stdlib_ver@".jar \
           "@stdlib_sha256@"
fi

if ! [ -e "$ks_home"/kotlin_script-"@ks_jar_ver@".jar ]; then
  : ${repo:="https://repo1.maven.org/maven2"}
  : ${local_repo:="$HOME/.m2/repository"}
  : ${fetch_cmd:="$(command -v fetch || command -v curl) -o-"}

  do_fetch "$ks_home"/kotlin_script-"@ks_jar_ver@".jar \
           org/cikit/kotlin_script/kotlin_script/"@ks_jar_ver@"/kotlin_script-"@ks_jar_ver@".jar \
           "@ks_jar_sha256@"
fi

mkdir -p "$ks_home"/cache/work

if ! tmpfile="$(mktemp "$ks_home"/cache/work/XXXXXXXXXXXXXXXX)"; then
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
                 -M "$tmpfile" \
                 -d "$tmpfile".jar \
                 "$script_file"; then
  rm -f "$tmpfile" "$tmpfile".jar
  exit 1
fi

chk=
inc=
while read line; do
  case "$line" in
  '///INC='*)
    inc="${line#///INC=}"
    ;;
  '///CHK=sha256='*)
    sha256="${line#///CHK=sha256=}"
    if [ x"$inc" = x ]; then
      script_sha256="$sha256"
      script_cache_dir="$ks_home"/cache-@ks_jar_ver@/"${script_sha256%??????????????????????????????????????????????????????????????}"
      script_cache="$script_cache_dir"/"${script_sha256#??}"
      chk="${chk}$sha256 *$script_name"$'\n'
    else
      chk="${chk}$sha256 *$inc"$'\n'
    fi
    ;;
  esac
done < "$tmpfile"

target_sha256="$(${sha256_cmd} << __EOF__
$chk
__EOF__
)" || exit 1
target_sha256="${target_sha256%% *}"
target_dir="$ks_home"/cache-@ks_jar_ver@/"${target_sha256%??????????????????????????????????????????????????????????????}"
target="$target_dir"/"${target_sha256#??}".jar

if ! mkdir -p "$target_dir" "$script_cache_dir"; then
  rm -f "$tmpfile" "$tmpfile".jar
  exit 1
fi

if ! mv -f "$tmpfile" "$script_cache".metadata; then
  rm -f "$tmpfile" "$tmpfile".jar
  exit 1
fi

if ! mv -f "$tmpfile".jar "$target"; then
  rm -f "$tmpfile" "$tmpfile".jar
  exit 1
fi

exec ${java_cmd} -jar "$target" "$@"
exit 2
