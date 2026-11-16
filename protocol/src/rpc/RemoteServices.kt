package org.example.kotlineditor.rpc

import org.example.kotlineditor.model.AppServices

/** Every IDE service, backed by a server. Each one lives next to the codecs of its feature, in the rpc files. */
fun RpcClient.appServices(): AppServices =
    AppServices(
        fileSystem = RemoteFileSystem(this),
        search = RemoteSearch(this),
        recentProjects = RemoteRecentProjects(this),
        places = RemotePlaces(this),
        terminal = RemoteTerminalLauncher(this),
        sessions = RemoteSessions(this),
        git = RemoteGit(this),
        connection = status,
        connections = connections,
        network = network,
        languageServers = RemoteLanguageServers(this),
        telemetry = RemoteTelemetrySettings(this),
        disk = RemoteDiskWatch(this),
        agents = RemoteAgents(this),
        runner = RemoteRunner(this),
        plugins = RemotePluginServer(this),
        deployer = RemoteDeployer(this),
        deployProfiles = RemoteDeployProfiles(this),
    )
