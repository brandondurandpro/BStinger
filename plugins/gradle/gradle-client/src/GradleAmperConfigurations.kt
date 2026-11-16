package org.example.kotlineditor.run

import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.RunConfiguration
import org.example.kotlineditor.state.RunConfigurationsSource
import org.example.kotlineditor.state.RunKind

/** The run configurations a Gradle or Amper project suggests from the wrappers at its root. */
class GradleAmperConfigurations : RunConfigurationsSource {
    /**
     * An Amper project (`kotlin` or `amper` wrapper) and a Gradle one (`gradlew`) each get a test run and an
     * application run. [windows] picks the `.bat` wrappers, which are the ones a Windows server can run; they are
     * written `.\name`, as Windows looks a program up in its own directory, not in the one the command runs in.
     */
    override suspend fun configurations(entries: List<FileEntry>, windows: Boolean): List<RunConfiguration> {
        val names = entries.map { it.name }.toSet()
        val suggestions = ArrayList<RunConfiguration>()
        val amper = listOf("kotlin", "amper").firstOrNull { it in names }
        if (amper != null) {
            val wrapper = if (windows) ".\\$amper.bat" else "./$amper"
            suggestions += RunConfiguration("amper-test", "All tests (Amper)", RunKind.Tests, "$wrapper test")
            suggestions += RunConfiguration("amper-run", "Run (Amper)", RunKind.Run, "$wrapper run")
        }
        if ("gradlew" in names) {
            val wrapper = if (windows) ".\\gradlew.bat" else "./gradlew"
            suggestions += RunConfiguration("gradle-test", "All tests (Gradle)", RunKind.Tests, "$wrapper test")
            suggestions += RunConfiguration("gradle-run", "Run (Gradle)", RunKind.Run, "$wrapper run")
        }
        return suggestions
    }
}
