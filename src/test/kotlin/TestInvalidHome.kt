import assertk.assertThat
import assertk.assertions.contains
import kotlin.io.path.readLines

class TestInvalidHome {
    @Test
    fun `don't try to store into invalid home`() {
        runScript(
            "test_invalid_home.out",
            "env", *env, "HOME=/nowhere", "M2_LOCAL_REPO=",
            "script_file=test.kt",
            zsh, "-xy", "test.kt"
        )
        val out = baseDir.resolve("test_invalid_home.out").readLines()
        // temp runner should be cleaned up at end
        assertThat(out.last()).contains("rm -f ")
    }
}
