# kotlin_script


## Installation with gradle

Published versions are installed automatically by the embedded installer
 (see below).
 
You can easily install any version of `kotlin_script` (linked with any 
 kotlin version) with the following gradle tasks.
 
```
# update sources with dependency information
./gradlew updateMainSources updateLauncherSources

# build and install kotlin_script into local repository
./gradlew installMainJar

# run tests
./src/test/kotlin/run_tests.kt
```


## Usage

This version of `kotlin_script` is used by embedding
 an installer shell script snippet directly into your script file. 

```Sh
#!/bin/sh

/*/ __kotlin_script_installer__ 2>&-
# vim: syntax=kotlin
#    _         _   _ _                       _       _
#   | |       | | | (_)                     (_)     | |
#   | | _____ | |_| |_ _ __    ___  ___ _ __ _ _ __ | |_
#   | |/ / _ \| __| | | '_ \  / __|/ __| '__| | '_ \| __|
#   |   < (_) | |_| | | | | | \__ \ (__| |  | | |_) | |_
#   |_|\_\___/ \__|_|_|_| |_| |___/\___|_|  |_| .__/ \__|
#                         ______              | |
#                        |______|             |_|
v=1.9.21.22
p=org/cikit/kotlin_script/"$v"/kotlin_script-"$v".sh
url="${M2_CENTRAL_REPO:=https://repo1.maven.org/maven2}"/"$p"
kotlin_script_sh="${M2_LOCAL_REPO:-"$HOME"/.m2/repository}"/"$p"
if ! [ -r "$kotlin_script_sh" ]; then
  kotlin_script_sh="$(mktemp)" || exit 1
  fetch_cmd="$(command -v curl) -kfLSso" || \
    fetch_cmd="$(command -v fetch) --no-verify-peer -aAqo" || \
    fetch_cmd="wget --no-check-certificate -qO"
  if ! $fetch_cmd "$kotlin_script_sh" "$url"; then
    echo "failed to fetch kotlin_script.sh from $url" >&2
    rm -f "$kotlin_script_sh"; exit 1
  fi
  dgst_cmd="$(command -v openssl) dgst -sha256 -r" || dgst_cmd=sha256sum
  case "$($dgst_cmd < "$kotlin_script_sh")" in
  "4b91daa5e8287db64c1c0f84b88ea8f4498dfba37dd73c0b7ddbce86e4ada107 "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/
```

Also, the script file has to be a regular kotlin source file with
additional metadata and a main function/method.

There is an initial kts support, however script templates (like main-kts) are 
not directly supported. When using main-kts, the flat (locked) list of 
dependencies has to be specified within `///DEP` comments (see below).

You can try an example:

```
./examples/hello.kt
```

The file is compiled to a jar file and stored into the local repository with a 
group id of `org.cikit` and an artifact id of `kotlin_script_cache`. 
Further invocations will compare a checksum of the file against the cached 
version to determine if recompilation is required.


## Variables

The following shell variables are used in kotlin_script.sh.

* `kotlin_script_sh` - path to fetched kotlin_script.sh
* `script_file` - path to executed script (default `$0`)
* `java_cmd` - command and args to invoke jvm (default `java`) 

Environment variables

* `M2_CENTRAL_REPO` - maven2 repository url to fetch missing dependency artifacts
* `M2_LOCAL_REPO` - local maven2 repository populated with dependency artifacts
* `M2_LOCAL_MIRROR` - read-only local maven2 repository

## Metadata

The metadata format within the source files is currently rather simple. 

```
///MAIN=some.package.MainClassName

# compiler plugin
///PLUGIN=org.jetbrains.kotlin:kotlin-serialization

# simple compile dependency
///DEP=group.id:artifact.id:1.0.0

# compile dependency with checksum, classifier and extension
///DEP=group.id:artifact.id:1.0.0:jdk11@jar:sha256=...

# runtime dependency
///RDEP=group.id:artifact.id:1.0.0
```

The kotlin std- and reflection- libs are implicitly added to the compilation
class path.

All dependencies have to be listed explicitly and `kotlin_script` does not 
include any kind of resolver. It is easy to "lock" the dependencies like 
this, however that lock specification has to be crafted manually for now.



## Roadmap

* Generate dependency lock specification from `@file:DependsOn` annotations
