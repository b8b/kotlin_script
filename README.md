# kotlin_script


## Installation with gradle

Published versions are installed automatically by the embedded installer
 (see below).
 
You can easily install any version of `kotlin_script` (linked with any 
 kotlin version) with the following gradle task.
 
```
v=1.6.10.16
./gradlew jar runnerJar sourcesJar dokkaJar copyDependencies

# run install script with embedded kotlin_script installer
./examples/install.kt build/libs/kotlin_script-${v}.jar

# alternatively, compile and run install script manually
./gradlew examplesClasses
java -cp build/libs/kotlin_script-${v}.jar:build/classes/kotlin/examples InstallKt build/libs/kotlin_script-${v}.jar

# run tests
./examples/test.kt
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
v=1.6.10.16
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
  "15559e330245c72df5b1a85d592319d9809a3779b5cf76924ea8fef36aee9986 "*) ;;
  *) echo "error: failed to verify kotlin_script.sh" >&2
     rm -f "$kotlin_script_sh"; exit 1;;
  esac
fi
. "$kotlin_script_sh"; exit 2
*/
```

Also, the script file has to be a regular kotlin source file with
additional meta data and a main function/method (no kts support for now).

You can try an example:

```
./examples/hello.kt
```

The file is compiled to a jar file and stored into the local repository with a 
group id of `org.cikit` and an artifact id of `kotlin_script_cache`. 
Further invocations will compare a checksum of the file against the cached 
version to determine if recompilation is required.


## Variables

Some shell variables can be customized in the embedded installer.

* `kotlin_script_sh` - path to kotlin_script-XX.sh
* `fetch_cmd` - command to fetch files
* `dgst_cmd` - command to calculate sha256 checksum of stdin
* `java_cmd` - command to start java vm (default `java`)
* `script_file` - path to executed script (default `$0`)

Environment variables

* `M2_CENTRAL_REPO` - maven2 repository url to fetch missing dependency artifacts
* `M2_LOCAL_REPO` - local maven2 repository populated with dependency artifacts
* `M2_LOCAL_MIRROR` - read-only local maven2 repository

## Metadata

The metadata format within the source files is currently rather simple. 

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

These shortcomings (and others) will be addressed in a future metadata
format. 


## Roadmap

* Support kts scripts and annotations for metadata
* Think of a sane dependency specification format  with full exclude and lock support
