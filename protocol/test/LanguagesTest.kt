package org.example.kotlineditor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguagesTest {
    @Test
    fun `typescript and javascript files share a language but keep their own identifier`() {
        assertEquals("typescript", Languages.languageOf("/p/a.ts"))
        assertEquals("typescript", Languages.languageOf("/p/a.tsx"))
        assertEquals("typescript", Languages.languageOf("/p/a.mjs"))
        assertEquals("typescript", Languages.languageOf("/p/A.JSX"))
        assertEquals("typescriptreact", Languages.identifierOf("/p/a.tsx"))
        assertEquals("javascriptreact", Languages.identifierOf("/p/a.jsx"))
        assertEquals("javascript", Languages.identifierOf("/p/a.cjs"))
        assertEquals("typescript", Languages.identifierOf("/p/a.mts"))
    }

    @Test
    fun `php keeps its language and files of other languages have none`() {
        assertEquals("php", Languages.languageOf("/p/a.php"))
        assertEquals("php", Languages.identifierOf("/p/a.phtml"))
        assertNull(Languages.languageOf("/p/a.kt"))
        assertNull(Languages.languageOf("/p/Makefile"))
    }
}
