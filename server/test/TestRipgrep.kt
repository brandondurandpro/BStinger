package org.example.kotlineditor.server

import org.example.kotlineditor.server.ripgrep.RipgrepBinary
import org.example.kotlineditor.server.ripgrep.RipgrepProvisioner
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.fail

/**
 * The pinned ripgrep for the tests, obtained like the server does: downloaded and verified once into
 * `~/.kotlin-editor/tools`, then reused. Tests needing it are skipped when it cannot be had (offline), except on CI
 * where a missing ripgrep is a failure.
 */
object TestRipgrep {
    private val binary: RipgrepBinary? by lazy {
        RipgrepProvisioner(RipgrepSettings(executable = null, bundledDirectory = null)).locate()
    }

    /** The executable, or the current test is skipped. */
    fun require(): RipgrepBinary {
        val found = binary
        if (found == null && System.getenv("CI") != null) fail("ripgrep could not be provisioned on CI")
        assumeTrue(found != null, "ripgrep unavailable (offline and not downloaded yet): test skipped")
        return checkNotNull(found)
    }
}
