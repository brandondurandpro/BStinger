package org.example.kotlineditor.state

/** Where the IDE gets the server it installs on a remote host. */
enum class RemoteBundleSource(val title: String, val description: String) {
    /** A bundle found on this machine (next to the app, in `~/.kotlin-editor/bundles`, or set by the developer). */
    Local("Local file", "The bundle shipped with the app or built with tools/remote-bundle.sh."),

    /** The bundle of the GitHub release of this IDE's version, downloaded once and kept. */
    GitHub("Download from GitHub", "The bundle of the GitHub release of this version, downloaded on first use."),
    ;

    companion object {
        /** The source saved in the preferences; [Local] when there is none or it is unknown. */
        fun fromSaved(name: String?): RemoteBundleSource = entries.firstOrNull { it.name == name } ?: Local
    }
}
