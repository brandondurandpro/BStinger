package org.example.kotlineditor.state

import org.example.kotlineditor.model.DeployProfile

/** Opens a new deployment filled like [profile], under a name that is not taken yet; nothing is saved until Save. */
fun DeploymentsState.duplicate(profile: DeployProfile) {
    edit(profile)
    val current = draft ?: return
    draft = current.copy(original = null, name = copyName(profile.name, profiles.map { it.name }.toSet()))
}

/** `Site copy`, then `Site copy 2`, `Site copy 3`…: the first of them that is not in [taken]. */
internal fun copyName(name: String, taken: Set<String>): String {
    val first = "$name copy"
    if (first !in taken) return first
    var number = 2
    while ("$first $number" in taken) number++
    return "$first $number"
}
