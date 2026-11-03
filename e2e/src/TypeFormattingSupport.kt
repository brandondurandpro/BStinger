package org.example.kotlineditor.e2e

/** A PHP function whose statement on line 3 lacks its `;`: typing ` ;` there, then Enter and `}`. */
val FORMAT_PHP: String = listOf("<?php", "function run()", "{", "    \$a = 1", "}", "").joinToString("\n")

/** The line of the statement of [FORMAT_PHP]. */
const val STATEMENT_LINE = 3

/** The statement once its `;` is typed and formatted. */
const val FORMATTED_STATEMENT = "    \$a = 1;"
