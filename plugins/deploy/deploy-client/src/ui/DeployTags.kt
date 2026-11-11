package org.example.kotlineditor.ui

/** Semantics test tags of the deployments, shared with the end-to-end tests. */
@Suppress("TooManyFunctions")
object DeployTags {
    const val DEPLOYMENTS = "deployments"
    const val DEPLOYMENTS_NEW = "deployments.new"
    const val DEPLOYMENTS_CLOSE = "deployments.close"
    const val DEPLOYMENTS_ERROR = "deployments.error"
    const val DEPLOYMENTS_EMPTY = "deployments.empty"
    const val DEPLOYMENTS_SUMMARY = "deployments.summary"
    const val DEPLOYMENT_NAME = "deployments.name"
    const val DEPLOYMENT_KIND = "deployments.kind"
    const val DEPLOYMENT_KIND_LOCAL = "deployments.kind.local"
    const val DEPLOYMENT_KIND_SSH = "deployments.kind.ssh"
    const val DEPLOYMENT_SSH_HOST = "deployments.sshHost"
    const val DEPLOYMENT_SSH_PICKER = "deployments.sshPicker"
    const val DEPLOYMENT_SSH_SEARCH = "deployments.sshSearch"
    const val DEPLOYMENT_SSH_NONE = "deployments.sshNone"
    const val DEPLOYMENT_ADD_FOLDER = "deployments.addFolder"
    const val DEPLOYMENT_FOLDERS_HINT = "deployments.foldersHint"
    const val DEPLOYMENT_DESTINATION = "deployments.destination"
    const val DEPLOYMENT_ADD_SELECTED = "deployments.addSelected"
    const val DEPLOYMENT_MODE = "deployments.mode"
    const val DEPLOYMENT_MODE_MANUAL = "deployments.mode.manual"
    const val DEPLOYMENT_SAVE = "deployments.save"
    const val DEPLOYMENT_CANCEL = "deployments.cancel"
    const val DEPLOYMENT_DRAFT_ERROR = "deployments.draftError"

    fun deployment(name: String) = "deployment:$name"

    fun deploymentRun(name: String) = "deployment.run:$name"

    fun deploymentEdit(name: String) = "deployment.edit:$name"

    fun deploymentDelete(name: String) = "deployment.delete:$name"

    fun deploymentStatus(name: String) = "deployment.status:$name"

    fun deploymentDuplicate(name: String) = "deployment.duplicate:$name"

    fun deploymentMenu(name: String) = "deployment.menu:$name"

    fun deploymentFiles(name: String) = "deployment.files:$name"

    fun deploymentFile(name: String, index: Int) = "deployment.file:$name:$index"

    fun deploymentKind(name: String) = "deployment.kind:$name"

    fun deploymentSshHost(alias: String) = "deployments.sshHost:$alias"

    fun deploymentSource(index: Int) = "deployments.source:$index"

    fun deploymentLanding(index: Int) = "deployments.landing:$index"

    fun deploymentRemoveFolder(index: Int) = "deployments.removeFolder:$index"

    fun deploymentTarget(index: Int) = "deployments.target:$index"

    fun deploymentTargetReset(index: Int) = "deployments.targetReset:$index"
}
