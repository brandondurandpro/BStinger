package org.example.kotlineditor.lsp.intelligence

import java.util.UUID

// Characters of a random UUID enough to tell the services of one machine apart.
private const val PREFIX_LENGTH = 8

/**
 * The start of the tokens of the lists a service offers to the client (code actions, code lenses): unique to it, so
 * that after a restart of the IDE server a token of the previous one never names a list of the new one.
 */
internal fun newTokenPrefix(): String = UUID.randomUUID().toString().take(PREFIX_LENGTH) + "-"
