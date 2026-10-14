package dev.typenil.vpnclient.core.subscription.model

/**
 * The user's persisted server pick, decoded from `selected_node_id`.
 * Automatic modes have dedicated ids that map to separate sing-box urltest groups.
 */
sealed interface NodeSelection {
    /** Lowest-latency server from non-Russian nodes. */
    data object Auto : NodeSelection

    /** Lowest-latency server from nodes labelled as Russian. */
    data object AutoRussia : NodeSelection

    /** A concrete node by id. */
    data class Node(
        val id: String,
    ) : NodeSelection

    companion object {
        const val AUTO_ID = "auto"
        const val AUTO_RUSSIA_ID = "auto-ru"

        fun fromId(id: String?): NodeSelection? =
            when (id) {
                null -> null
                AUTO_ID -> Auto
                AUTO_RUSSIA_ID -> AutoRussia
                else -> Node(id)
            }

        fun NodeSelection.toId(): String =
            when (this) {
                Auto -> AUTO_ID
                AutoRussia -> AUTO_RUSSIA_ID
                is Node -> id
            }
    }
}
