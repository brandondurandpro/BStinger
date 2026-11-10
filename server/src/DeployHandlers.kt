package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.deployDestination
import org.example.kotlineditor.rpc.deployItems
import org.example.kotlineditor.rpc.deployProfiles
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.sshHostsJson
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.toJson

/** Deployments: copying files of a project to a folder, and the deployments saved for each project. */
internal fun deployHandlers(services: LocalServices): Map<Method, Handler> =
    mapOf(
        Method.Deploy to {
            val items = params.read("items") { it.deployItems() }
            val destination = params.read("destination") { it.deployDestination() }
            services.deployer.deploy(entry("root"), items, destination).toJson()
        },
        Method.DeploySshHosts to { services.deployer.sshHosts().sshHostsJson() },
        Method.DeployProfilesLoad to { services.deployProfiles.load(params.string("project")).toJson() },
        Method.DeployProfilesSave to {
            val profiles = params.read("profiles") { it.deployProfiles() }
            nothing { services.deployProfiles.save(params.string("project"), profiles) }
        },
    )
