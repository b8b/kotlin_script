import assertk.assertThat
import assertk.assertions.hasClass
import assertk.assertions.isFailure
import java.nio.file.Files
import kotlin.io.path.writeText

class TestBasic {
    @Test
    fun `run from scratch`() {
        compileOk()
    }

    @Test
    fun `run with everything cached`() {
        compileOk()
        runScript(
            "test_all_cached.out",
            "env", *env, "script_file=test.kt",
            zsh, "-xy", "test.kt"
        )
    }

    @Test
    fun `fail on compiler error`() = assertThat {
        baseDir.resolve("test_err.kt").writeText("hello there\n")
        runScript(
            "test_compile_error.out",
            "env", *env, "script_file=test_err.kt",
            zsh, "-xy", "test.kt"
        )
    }.isFailure().hasClass(IllegalStateException::class)

    @Test
    fun `fail on invalid include`() = assertThat {
        baseDir.resolve("test_inv_inc.kt").writeText(
            "///INC=nowhere.kt\n"
        )
        runScript(
            "test_compile_inv_inc.out",
            "env", *env, "script_file=test_inv_inc.kt",
            zsh, "-xy", "test.kt"
        )
    }.isFailure().hasClass(IllegalStateException::class)

    @Test
    fun `fail on invalid dependency`() = assertThat {
        baseDir.resolve("test_inv_dep.kt").writeText(
            "///DEP=nowhere:nothing:1.0\n"
        )
        runScript(
            "test_inv_dep.out",
            "env", *env, "script_file=test_inv_dep.kt",
            zsh, "-xy", "test.kt"
        )
    }.isFailure().hasClass(IllegalStateException::class)

    @Test
    fun `run with clean cache`() {
        compileOk()
        cleanup(cache)
        runScript(
            "test_copy_from_local_repo.out",
            "env", *env, "script_file=test.kt",
            zsh, "-xy", "test.kt"
        )
    }

    @Test
    fun `fail on bad local repo`() = assertThat {
        compileOk()
        cleanup(cache)
        val f = localRepo.resolve(
            "org/cikit/kotlin_script/$v/kotlin_script-$v.jar"
        )
        Files.createDirectories(f.parent)
        f.writeText("broken!")
        runScript(
            "test_bad_local_repo.out",
            "env", *env, "script_file=test.kt",
            zsh, "-xy", "test.kt"
        )
    }.isFailure().hasClass(IllegalStateException::class)
}
