# kotlin_script

## Installation

Make sure you have a Java Runtime installed on your system. Any JRE from https://adoptopenjdk.net/ should be fine.
Run the installed script `install.sh`.

The script runs through the following simple steps:

1. grab a kotlin compiler and std libs from your local maven repository or fetch them with curl if not found

2. compile src to kotlin_script_installer.jar with -include-runtime and Main-Class entry in manifest

3. run java -jar kotlin_script_installer.jar

The third step installs kotlin_script into the users home directory (~/.kotlin_script). 
In order to use the tool, the `PATH` variable has to be adapted:

```
export PATH=$PATH:$HOME/.kotlin_script/bin
```

## Usage

The `kotlin_script` tool currently doesn't implement any options. The first argument is interpreted as the
path to the main script file containing the program to compile and run.

The file has to be a regular kotlin source file (kotlin scripting might be supported at a later time) with
additional meta data and a main function/method.

You can try an example:

```
kotlin_script examples/check_kotlin_update.kt
```

The file is compiled to a jar file and stored into `~/.kotlin_script/cache`. 
Further invocations will compare a checksum of the file against the embedded meta data within
the compiled jar file to determine if recompilation is required.

## Metadata

The meta data format within the source files is currently the same rather simple format also used within
the compiled jar files. The only required information is the main class name:

```
///MAIN=some.package.MainClassName

# simple compile dependency
///DEP=group.id:artifact.id:1.0.0

# compile dependency with checksum and extension
///DEP=group.id:artifact.id:1.0.0:.jar:sha256=...

# runtime dependency
///RDEP=group.id:artifact.id:1.0.0
```

Dependencies are not "resolved" by kotlin_script. A maven dependency report can be used to provide the 
resolved dependency list.

## Roadmap

* Split code into separate "up-to-date check" and compiler
* Implement up-to-date check in C for reasonable startup times
* Use annotations for meta data in source files
* Implement dependency isolation mode
