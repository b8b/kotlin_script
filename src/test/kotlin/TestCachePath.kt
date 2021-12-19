import assertk.assertThat
import assertk.assertions.isEmpty
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.io.path.writer

class TestCachePath {
    @Test
    fun `runner and KotlinScript calculate same jar cache location`() {
        val incSubDir = baseDir.resolve("util")
        Files.createDirectories(incSubDir)
        incSubDir.resolve("inc.kt").writeText("fun myFunc() = 1\n")
        baseDir.resolve("test.kt").writer().use { w ->
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
        runScript("test_compile_ok.out",
            "env", *env, "script_file=test.kt",
            zsh, "-xy", "test.kt")
        val logFileName = "test_all_cached.out"
        runScript(
            logFileName,
            "env", *env, "script_file=test.kt",
            zsh, "-xy", "test.kt"
        )
        val lines = Files.newBufferedReader(baseDir.resolve(logFileName)).readLines()
        assertThat(lines.filter { line -> "compileScript" in line }).isEmpty()
    }
}
