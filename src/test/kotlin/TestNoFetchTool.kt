import assertk.assertFailure
import assertk.assertions.hasClass
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TestNoFetchTool {
    @Test
    fun `without any fetch tool available throw an exception`() = assertFailure {
        compileOk()
        cleanup(cache)
        val subDir = "org/cikit/kotlin_script/$v"
        listOf(
            localRepo.resolve("$subDir/kotlin_script-$v.sh"),
            localRepo.resolve("$subDir/kotlin_script-$v.jar"),
            binDir.resolve("fetch"),
            binDir.resolve("curl"),
            binDir.resolve("wget")
        ).forEach { f -> Files.deleteIfExists(f) }
        runScript(
            "test_no_fetch_tool.out",
            "env", *env, "script_file=test.kt",
            zsh, "-xy", "test.kt"
        )
    }.hasClass(IllegalStateException::class)
}
