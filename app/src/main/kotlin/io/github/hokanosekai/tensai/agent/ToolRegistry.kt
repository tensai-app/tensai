package io.github.hokanosekai.tensai.agent

/**
 * Which tiers the user granted. The storage and the Settings screen are TEN-174, which replaces
 * [DefaultTierGrants]; this is only the seam [ToolRegistry] reads.
 */
fun interface TierGrants {
    fun granted(): Set<ToolTier>
}

/** Local and phone actions on, Android permissions and Network off, as the spec's defaults say. */
object DefaultTierGrants : TierGrants {
    override fun granted(): Set<ToolTier> = setOf(ToolTier.Local, ToolTier.PhoneAction)
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

    companion object {
        /**
         * The built-in tools: the two that need nothing from Android, then [platformTools] (device info,
         * chat search, clipboard, torch, memory), which the app builds because they need a `Context` or the database.
         */
        fun default(grants: TierGrants = DefaultTierGrants, platformTools: List<Tool> = emptyList()) =
            ToolRegistry(listOf(DateTimeTool(), CalculateTool()) + platformTools, grants)
    }
}
