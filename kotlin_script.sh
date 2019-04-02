: ${ks_home:="$HOME/.kotlin_script"}
: ${java_cmd:="java"}
: ${sha256_cmd:="openssl dgst -sha256 -r"}
: ${script_file:="$0"}

script_dir="$(dirname "$script_file")"
script_name="$(basename "$script_file")"
script_sha256="$(${sha256_cmd} < "$script_file")"
script_sha256="${script_sha256%% *}"
script_sha_hi="${script_sha256%??????????????????????????????????????????????????????????????}"
script_sha_lo="${script_sha256#??}"
script_cache_dir="$ks_home"/cache/"$script_sha_hi"
script_cache="$script_cache_dir"/"$script_sha_lo"

target=

parse_script_metadata()
{
  local line=
  while read line; do
    case "$line" in
    '///INC='*)
      local inc="${line#///INC=}"
      set -- "$@" "$inc"
      ;;
    esac
  done < "$script_cache".metadata
  local chk=
  if [ "$#" -gt 0 ]; then
    if ! chk="$(cd "$script_dir" && ${sha256_cmd} "$@")"$'\n'; then
      return
    fi
  fi
  chk="$script_sha256 *$script_name"$'\n'"$chk"
  local target_sha256="$(${sha256_cmd} << __EOF__
$chk
__EOF__
)"
  target_sha256="${target_sha256%% *}"
  local target_sha_hi="${target_sha256%??????????????????????????????????????????????????????????????}"
  local target_sha_lo="${target_sha256#??}"
  local target_cache="$ks_home"/cache/"$target_sha_hi"/"$target_sha_lo"
  target="$target_cache".jar
}

if [ -e "$script_cache".metadata ]; then
  parse_script_metadata
fi

java_cmd=java
if ! which "$java_cmd" >/dev/null 2>&1; then
  echo "java installation not implemented!" >&2
  exit 1
fi

if [ -e "$target" ]; then
  exec ${java_cmd} -jar "$target" "$@"
  exit 2
fi

: ${repo:="https://repo1.maven.org/maven2"}
: ${local_repo:="$HOME/.m2/repository"}

if which fetch >/dev/null 2>&1; then
  : ${fetch_cmd:="fetch -aAqo"}
else
  : ${fetch_cmd:="curl -sSfo"}
fi

do_fetch()
{
  local dest="$1"
  local src="$2"
  local sha256="$3"
  mkdir -p "$(dirname "$dest")"
  if [ -e "$local_repo"/"$src" ]; then
    cp -f "$local_repo"/"$src" "$dest"~
  fi
  local retry=3
  while true; do
    if [ -e "$dest"~ ]; then
      case "$(${sha256_cmd} < "$dest"~)" in
      "$sha256 "*)
        mv -f "$dest"~ "$dest"
        return
        ;;
      esac
    fi
    if ! [ "$retry" -gt 0 ]; then
      break
    fi
    retry=$(($retry - 1))
    if ! ${fetch_cmd} "$dest"~ "$repo"/"$src"; then
      sleep 1
    fi
  done
  echo "error: failed to fetch $repo/$src" >&2
  exit 1
}

stdlib_ver="@stdlib_ver@"
stdlib_sha256="@stdlib_sha256@"
stdlib_subdir=org/jetbrains/kotlin/kotlin-stdlib/"$stdlib_ver"
stdlib_filename=kotlin-stdlib-"$stdlib_ver".jar
stdlib_dir="$ks_home"/kotlin-compiler-"$stdlib_ver"/kotlinc/lib
stdlib="$stdlib_dir"/kotlin-stdlib.jar

if ! [ -e "$stdlib" ]; then
  do_fetch "$stdlib" "$stdlib_subdir"/"$stdlib_filename" "$stdlib_sha256"
fi

ks_jar_ver="@ks_jar_ver@"
ks_jar_sha256="@ks_jar_sha256@"
ks_jar_subdir=org/cikit/kotlin_script/kotlin_script/"$ks_jar_ver"
ks_jar_filename=kotlin_script-"$ks_jar_ver".jar
ks_jar="$ks_home"/"$ks_jar_filename"

if ! [ -e "$ks_jar" ]; then
  do_fetch "$ks_jar" "$ks_jar_subdir"/"$ks_jar_filename" "$ks_jar_sha256"
fi

mkdir -p "$ks_home"/cache/work

if ! tmpfile="$(mktemp "$ks_home"/cache/work/XXXXXXXXXXXXXXXX)"; then
  exit 1
fi

if ! ${java_cmd} -Dmaven.repo.url="$repo" \
                 -Dmaven.repo.local="$local_repo" \
                 -Dkotlin_script.home="$ks_home" \
                 -jar "$ks_jar" \
                 -M "$tmpfile" \
                 -d "$tmpfile".jar \
                 "$script_file"; then
  rm -f "$tmpfile" "$tmpfile".jar
  exit 1
fi

parse_compiler_metadata()
{
  local chk=
  local inc=
  local line=
  while read line; do
    case "$line" in
    '///INC='*)
      inc="${line#///INC=}"
      ;;
    '///CHK=sha256='*)
      local sha256="${line#///CHK=sha256=}"
      if [ x"$inc" = x ]; then
        script_sha256="$sha256"
        script_sha_hi="${script_sha256%??????????????????????????????????????????????????????????????}"
        script_sha_lo="${script_sha256#??}"
        script_cache_dir="$ks_home"/cache/"$script_sha_hi"
        script_cache="$script_cache_dir"/"$script_sha_lo"
        chk="${chk}$sha256 *$script_name"$'\n'
      else
        chk="${chk}$sha256 *$inc"$'\n'
      fi
      ;;
    esac
  done < "$1"
  local target_sha256="$(${sha256_cmd} << __EOF__
$chk
__EOF__
)"
  target_sha256="${target_sha256%% *}"
  local target_sha_hi="${target_sha256%??????????????????????????????????????????????????????????????}"
  local target_sha_lo="${target_sha256#??}"
  target_dir="$ks_home"/cache/"$target_sha_hi"
  local target_cache="$ks_home"/cache/"$target_sha_hi"/"$target_sha_lo"
  target="$target_cache".jar
}

parse_compiler_metadata "$tmpfile"

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
