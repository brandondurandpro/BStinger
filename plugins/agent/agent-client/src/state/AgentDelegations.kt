package org.example.kotlineditor.state

// The conversations of the members a manager delegates to in relay mode (`<manager key>~d<n>` on the server): the
// server keeps them out of the history, and the IDE follows one only while its run is shown. They are no conversation
// of the user, so they are never listed.

private const val DELEGATION_MARK = "~d"

/** Whether [key] names a delegation of the conversation [parent] on the server. */
internal fun isDelegationOf(parent: String, key: String): Boolean = key.startsWith(parent + DELEGATION_MARK)

/** The hidden conversation that follows the delegation [key], started on first use; [title] is the member's name. */
internal fun AgentState.followDelegation(key: String, title: String): Conversation {
    val known = delegations[key]
    if (known != null) return known
    val followed = Conversation(DELEGATION_ID, title, "", key)
    followed.delegation = true
    delegations[key] = followed
    project.followTranscript(followed)
    return followed
}

/** Stops following the delegation [key]: its transcript is no longer asked for. */
internal fun AgentState.unfollowDelegation(key: String) {
    delegations.remove(key)?.polling?.cancel()
}

/** Stops following every delegation of [parent]: it was closed or deleted. */
internal fun AgentState.unfollowDelegations(parent: Conversation) {
    val keys = delegations.keys.filter { isDelegationOf(parent.key, it) }
    keys.forEach { unfollowDelegation(it) }
}

private const val DELEGATION_ID = -1
