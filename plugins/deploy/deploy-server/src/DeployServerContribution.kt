package org.example.kotlineditor.deploy.server

import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.ServerContribution

/** Found by the server through `META-INF/services`. */
class DeployServerContribution : ServerContribution {
    override fun handlers(services: LocalServices) = deployHandlers(services)
}
