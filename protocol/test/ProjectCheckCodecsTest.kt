package org.example.kotlineditor.rpc

import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FileDiagnostics
import org.example.kotlineditor.model.ProjectCheckResult
import org.example.kotlineditor.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectCheckCodecsTest {
    @Test
    fun `a project check survives the wire`() {
        val problem = Diagnostic(TextRange(1, 2, 1, 5), DiagnosticSeverity.Error, "bad", "vue-tsc", "TS2322")
        val result =
            ProjectCheckResult(
                FeatureStatus.Ready,
                "vue-tsc",
                "1 problems in 1 files",
                listOf(FileDiagnostics("/p/a.vue", listOf(problem))),
            )
        assertEquals(result, result.toJson().projectCheckResult())
    }

    @Test
    fun `a check that could not run keeps its reason`() {
        val result = ProjectCheckResult(FeatureStatus.NotReady, "", "No tsconfig.json in the project", emptyList())
        assertEquals(result, result.toJson().projectCheckResult())
    }
}
