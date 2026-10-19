package io.github.tensaiapp.agent

/**
 * Which tiers the user granted: the seam [ToolRegistry] reads. The persisted one is
 * [io.github.tensaiapp.data.ToolSettingsRepository] (TEN-174).
 */
fun interface TierGrants {
    fun granted(): Set<ToolTier>
}

/**
 * Local, phone actions and Android permissions on, Network off. The permission tier is on because Android
 * asks for each permission at first use; Settings shows it under "Phone actions".
 */
object DefaultTierGrants : TierGrants {
    override fun granted(): Set<ToolTier> = setOf(ToolTier.Local, ToolTier.PhoneAction, ToolTier.AndroidPermission)
}

/**
 * The tools the app knows, and the ones the model gets to see: [active] filters them by the granted
 * tiers, read at each call so a grant changed in Settings applies to the next turn.
 */
class ToolRegistry(
    tools: List<Tool> = emptyList(),
    private val grants: TierGrants = DefaultTierGrants,
) {
    private val all = LinkedHashMap<String, Tool>()

    init {
        tools.forEach(::register)
    }

    /** @throws IllegalArgumentException when a tool of that name is already registered. */
    fun register(tool: Tool) {
        require(all.putIfAbsent(tool.name, tool) == null) { "Tool '${tool.name}' is already registered" }
    }

    /** The tools whose tier is granted, in registration order. */
    fun active(): List<Tool> {
        val granted = grants.granted()
        return all.values.filter { it.tier in granted }
    }

    /** Every registered tool whatever the grants, in registration order: what Settings lists per tier. */
    fun registered(): List<Tool> = all.values.toList()

    /** The registered tool called [name], granted or not: a past call is still read against it. */
    fun find(name: String): Tool? = all[name]

    companion object {
        /**
         * The built-in tools: the four that need nothing from Android, then [platformTools] (device info,
         * chat search, clipboard, torch, memory, ask_user), which the app builds because they need a `Context` or the database.
         */
        fun default(grants: TierGrants = DefaultTierGrants, platformTools: List<Tool> = emptyList()) =
            ToolRegistry(listOf(DateTimeTool(), CalculateTool(), ConvertTool(), ConvertTimeTool()) + platformTools, grants)
    }
}
