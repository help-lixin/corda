package net.corda.networkcloner.entity

import net.corda.networkcloner.api.NodeDatabase

data class MigrationTask(val identity: Identity,
                         val sourceNodeDatabase: NodeDatabase,
                         val destinationNodeDatabase: NodeDatabase,
                         val migrationContext: MigrationContext)
