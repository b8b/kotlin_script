# kotlin_script


## Installation with gradle

Published versions are installed automatically by the embedded installer
 (see below).
 
You can easily install any version of `kotlin_script` (linked with any 
 kotlin version) with the following gradle task.
 
```
./gradlew publishToMavenLocal
```


## Usage

This version of `kotlin_script` is used by embedding
 an installer shell script snippet directly into your script file. 

```Sh
#!/bin/sh

/*__kotlin_script_installer__/ 2>/dev/null
# vim: syntax=kotlin
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=1.3.31.1
artifact=org/cikit/kotlin_script/kotlin_script/"$v"/kotlin_script-"$v".sh
repo=${repo:-https://repo1.maven.org/maven2}
if ! [ -e "${local_repo:=$HOME/.m2/repository}"/"$artifact" ]; then
  fetch_s="$(command -v fetch) -aAqo" || fetch_s="$(command -v curl) -fSso"
  mkdir -p "$local_repo"/org/cikit/kotlin_script/kotlin_script/"$v"
  tmp_f="$(mktemp "$local_repo"/"$artifact"~XXXXXXXXXXXXXXXX)" || exit 1
  if ! ${fetch_cmd:="$fetch_s"} "$tmp_f" "$repo"/"$artifact"; then
    echo "error: failed to fetch kotlin_script" >&2
    rm -f "$tmp_f"; exit 1
  fi
  case "$(openssl dgst -sha256 -r < "$tmp_f")" in
  "d6cd4372ee10a2b2ff0db496a376be56823f0b7a6f36ccd9aaf8b9e7a26089b0 "*)
    mv -f "$tmp_f" "$local_repo"/"$artifact" ;;
  *)
    echo "error: failed to validate kotlin_script" >&2
    rm -f "$tmp_f"; exit 1 ;;
  esac
fi
. "$local_repo"/"$artifact"
exit 2
*/
```

Also, the script file has to be a regular kotlin source file with
additional meta data and a main function/method (no kts support for now).

You can try an example:

```
./examples/hello.kt
```

The file is compiled to a jar file and stored into `~/.kotlin_script/cache`. 
Further invocations will compare a checksum of the file against the cached 
version to determine if recompilation is required.


## Shell variables

Some shell variables can be customized in the embedded installer.

* `repo` - maven2 repository url to fetch bootstrap items from
* `local_repo` - local maven2 repository to fetch items into
* `ks_home` - installation path of `kotlin_script` (default `~/.kotlin_script`)
* `java_cmd` - command to start java vm (default `java`)


## Metadata

The meta data format within the source files is currently rather simple. 

```
///MAIN=some.package.MainClassName

# simple compile dependency
///DEP=group.id:artifact.id:1.0.0

# compile dependency with checksum, classifier and extension
///DEP=group.id:artifact.id:1.0.0:jdk11@jar:sha256=...

# runtime dependency
///RDEP=group.id:artifact.id:1.0.0
```

The kotlin std- and reflection- libs are implicitly added to the compilation
class path.

Dependencies are not "resolved" by `kotlin_script`. A maven dependency report 
can be used to provide the resolved dependency list.

These shortcomings (and others) will be addressed in a future meta data
format. 


## Roadmap

* Split code into separate "up-to-date check" and compiler (done)
* Implement up-to-date check in C for reasonable startup times (done in sh)
* Use a reasonable default for `///MAIN=` (done)
* Automatic upgrade
* Support compiling snippets from STDIN or command line arguments
* Use annotations for meta data in source files
* Implement dependency isolation mode
