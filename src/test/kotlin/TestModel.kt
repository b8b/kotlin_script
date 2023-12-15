import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin_script.Scope
import kotlin_script.parseDependency
import org.junit.jupiter.api.Test

class TestModel {
    @Test
    fun testParseDependencySpec() {
        val result = parseDependency("group.id:artifact-id:1.2:classifier@tar")
        assertThat(result.scope).isEqualTo(Scope.Compile)
        assertThat(result.sha256).isNull()
        assertThat(result.version).isEqualTo("1.2")
        assertThat(result.subPath).isEqualTo(
            "group/id/artifact-id/1.2/artifact-id-1.2-classifier.tar"
        )
        assertThat(result.type).isEqualTo("tar")
        assertThat(result.artifactId).isEqualTo("artifact-id")
        assertThat(result.classifier).isEqualTo("classifier")
    }
}
