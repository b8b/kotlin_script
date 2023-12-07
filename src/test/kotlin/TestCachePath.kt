import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import kotlin.io.path.*

class TestCachePath {
    @Test
    fun `launcher and KotlinScript calculate same jar cache location`() {
        val incSubDir = (baseDir / "util").createDirectories()
        (incSubDir / "inc.kt").writeText("fun myFunc() = 1\n")
        (baseDir / "test.kt").writer().use { w ->
            w.write(embeddedInstaller)
            w.write("""
                    |
                    |///INC=util/inc.kt
                    |
                    |fun main() {
                    |    if (myFunc() == 1) println("hello world!")
                    |}
                    |""".trimMargin())
        }
        runScript(
            "test_compile_ok.out",
            "env", *env, "script_file=test.kt",
            zsh, "-xy", "test.kt"
        )
        val logFileName = "test_all_cached.out"
        runScript(
            logFileName,
            "env", *env, "script_file=test.kt",
            zsh, "-xy", "test.kt"
        )
        val lines = (baseDir / logFileName).useLines { lines ->
            lines.filter { line -> "compileScript" in line }.toList()
        }
        assertThat(lines).isEmpty()
    }

    @Test
    fun `fetch missing dependency even if cached`() {
        (baseDir / "test2.kt").writer().use { w ->
            w.write(embeddedInstaller)
            w.write("""
                |
                |///DEP=net.java.dev.jna:jna:5.13.0
                |
                |fun main() {    
                |    println(com.sun.jna.Native.DEFAULT_ENCODING)
                |}
                |""".trimMargin())
        }
        runScript(
            "test2_compile_ok.out",
            "env", *env, "script_file=test2.kt",
            zsh, "-xy", "test2.kt"
        )
        (localRepo / "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar").deleteExisting()
        val logFileName = "test2_all_cached.out"
        runScript(
            logFileName,
            "env", *env, "script_file=test2.kt",
            zsh, "-xy", "test2.kt"
        )
        val lines = (baseDir / logFileName).useLines { lines ->
            lines.filter { line -> "compileScript" in line }.toList()
        }
        assertThat(lines).isNotEmpty()
    }
}
