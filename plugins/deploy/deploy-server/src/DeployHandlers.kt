package org.example.kotlineditor.deploy.server

import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.deployDestination
import org.example.kotlineditor.rpc.deployItems
import org.example.kotlineditor.rpc.deployProfiles
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.sshHostsJson
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.toJson
import org.example.kotlineditor.server.Handler
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.entry
import org.example.kotlineditor.server.nothing
import java.io.File

/** Deployments: copying files of a project to a folder, and the deployments saved for each project. */
internal fun deployHandlers(services: LocalServices): Map<MethodSpec, Handler> {
    val deployer = services.component("deploy.deployer") { LocalDeployer(services.ssh) }
    val deployProfiles =
        services.component("deploy.profiles") { FileDeployProfiles(File(services.dataDirectory, "deployments")) }
    return mapOf(
        Method.Deploy to {
            val items = params.read("items") { it.deployItems() }
            val destination = params.read("destination") { it.deployDestination() }
            deployer.deploy(entry("root"), items, destination).toJson()
        },
        Method.DeploySshHosts to { deployer.sshHosts().sshHostsJson() },
        Method.DeployProfilesLoad to { deployProfiles.load(params.string("project")).toJson() },
        Method.DeployProfilesSave to {
            val profiles = params.read("profiles") { it.deployProfiles() }
            nothing { deployProfiles.save(params.string("project"), profiles) }
        },
    )
}
