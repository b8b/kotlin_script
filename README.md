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
 an installer shell script snipped directly into your script file. 

Also, the script file has to be a regular kotlin source file with
additional meta data and a main function/method (no kts support for now).

You can try an example:

```
./src/test/kotlin/examples/check_kotlin_update.kt
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

The only required information is the main class name:

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
* Automatic upgrade
* Use a reasonable default for `///MAIN=`
* Support compiling snippets from STDIN or command line arguments
* Use annotations for meta data in source files
* Implement dependency isolation mode
