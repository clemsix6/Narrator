package io.github.clemsix6.narrator2.guard

import com.mojang.brigadier.ParseResults
import com.mojang.brigadier.context.CommandContextBuilder
import com.mojang.brigadier.tree.CommandNode
import net.minecraft.commands.CommandSourceStack
import java.util.IdentityHashMap

/**
 * What `run_command` may run (M1_M2 §10.6). The command is parsed first (by `run_command`, with the
 * source that will run it); then every command it would run is checked, behind `execute … run` and
 * `return run` too:
 * 1. against the forbidden roots: the built-in list [FORBIDDEN], written here, plus [extra]
 *    (`safety.extraForbiddenCommands`), which can only add roots. A few branches of other commands are
 *    forbidden as well ([FORBIDDEN_BRANCHES]: `execute store`, `random roll`…);
 * 2. against the reviewed ones: only the commands of [ALLOWED], plus [extraAllowed]
 *    (`safety.extraAllowedCommands`), may run. A command nobody reviewed for the Narrator (another mod's,
 *    or one a new version adds) is refused by default: the owner's server runs mods whose commands were
 *    never read.
 * Both settings are read at each check: `/narrator reload` may change them. The forbidden list wins over
 * the allowed ones.
 *
 * A forbidden command is caught three ways, so that neither an alias, a redirect nor a missing
 * permission hides it: by the first word of each command in the text (a command the source may not even
 * see, like `stop` at the gamemaster level, is refused rather than reported unknown), by every parsed
 * node that belongs to a forbidden command's tree, and by every redirect that lands in one (`tp` →
 * `teleport`). A command outside the allowed ones is caught by its first word, and by the command a
 * redirect lands in. A word that is no command at all is left to `run_command`, which reports it unknown.
 * `GuardPolicyGameTests` fails on any command of the server that is neither forbidden nor reviewed, so
 * that a new vanilla one is a decision, not an accident.
 */
class CommandPolicy(
    private val extraAllowed: () -> List<String> = { emptyList() },
    private val extra: () -> List<String>,
) {
    /**
     * Whether the parsed command may run. Valid or not: what was parsed is checked, so a forbidden command
     * is refused as such even when it is also malformed. Any thread (only the parse is read).
     */
    fun check(parse: ParseResults<CommandSourceStack>): Verdict = verdict(parse)

    /** [check] for any source type, so that it can be tested on a dispatcher of its own. */
    internal fun <S> verdict(parse: ParseResults<S>): Verdict {
        val input = parse.reader.string
        val root = parse.context.rootNode
        val extras = roots(extra)
        val owners = owners(root, extras)
        var context: CommandContextBuilder<S>? = parse.context
        while (context != null) {
            val word = if (context.rootNode === root) commandWord(input, context.range.start) else null
            val owner = word?.takeIf { it in FORBIDDEN || it in extras }
                ?: owners[context.rootNode]
                ?: context.nodes.firstNotNullOfOrNull { owners[it.node] }
            if (owner != null) return Verdict.Denied(Rules.FORBIDDEN_COMMAND, message(owner, extras))
            context = context.child
        }
        return unreviewed(parse, input, root)?.let { Verdict.Denied(Rules.FORBIDDEN_COMMAND, unreviewedMessage(it)) } ?: Verdict.Allowed
    }

    /** The first command [parse] would run that is not among the allowed ones, if any. */
    private fun <S> unreviewed(parse: ParseResults<S>, input: String, root: CommandNode<S>): String? {
        val allowed = ALLOWED + roots(extraAllowed)
        var commandOf: Map<CommandNode<S>, String>? = null
        var context: CommandContextBuilder<S>? = parse.context
        while (context != null) {
            val command = when {
                // A word that is no command runs nothing: run_command reports it unknown.
                context.rootNode === root -> commandWord(input, context.range.start).takeIf { root.getChild(it) != null }
                // A redirect into a command (`tp` → `teleport`, `execute as …` → `execute`).
                root.getChild(context.rootNode.name) === context.rootNode -> context.rootNode.name
                else -> (commandOf ?: commands(root).also { commandOf = it })[context.rootNode]
            }
            if (command != null && command !in allowed) return command
            context = context.child
        }
        return null
    }

    private fun roots(setting: () -> List<String>): Set<String> = setting().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Why a command is refused, and what to do instead. */
    enum class Reason(val text: String) {
        ADMIN("server administration is for the operators alone"),
        DEV("a development and debugging command, never for the Narrator"),
        WORLD("it changes the rules or the setup of the world"),
        CODE("it runs commands that were never checked"),
        BLOCKS("it changes blocks, and the players' constructions are never modified"),
        ITEMS("it creates, changes or destroys items, experience or progress outside the Narrator's ledgers"),
        ENTITIES("it acts on players or creatures outside the guarded tools"),
        SPEECH("the Narrator speaks through its speech tools (say, show_title, actionbar, give_book), which are recorded"),
        TOOL("a tool with its own limits does it"),
    }

    /**
     * A forbidden command.
     *
     * @property instead what to use instead, when a tool does it.
     */
    data class Forbidden(val reason: Reason, val instead: String? = null)

    companion object {
        /**
         * The built-in forbidden roots, vanilla 26.3 and this mod, with the reason of each. Commands of
         * permission level 3 and 4 are also invisible to the gamemaster source of `run_command`; they
         * are listed anyway, so that the rule does not depend on how the source is built.
         */
        val FORBIDDEN: Map<String, Forbidden> = buildMap {
            fun forbid(reason: Reason, vararg roots: String, instead: String? = null) =
                roots.forEach { put(it, Forbidden(reason, instead)) }

            // Server administration: stopping or saving, operators, bans, the whitelist, kicks,
            // transfers to another server, profilers, the idle timeout, LAN publishing, datapack
            // reloads, lookups of player profiles at Mojang, the world seed (a map of every
            // structure). `narrator` is the operators' control of the Narrator itself (chaos,
            // protections, whispers to the Director); `pray` is the players' channel to it.
            forbid(
                Reason.ADMIN,
                "stop", "save-all", "save-off", "save-on", "op", "deop", "ban", "ban-ip", "banlist", "pardon",
                "pardon-ip", "whitelist", "kick", "transfer", "debug", "perf", "jfr", "setidletimeout", "publish",
                "unpublish", "reload", "fetchprofile", "seed", "narrator",
            )
            // Registered only in a development environment, which is where the dev server and the
            // GameTests run: `raid` starts a raid, `warden_spawn_tracker` can summon a warden,
            // `spawn_armor_trims` spawns armor stands, `serverpack` pushes resource packs, `chase`
            // opens network connections, `debugconfig` changes a client's options, `test` builds,
            // runs and clears GameTest structures.
            forbid(
                Reason.DEV,
                "test", "raid", "chase", "debugpath", "debugmobspawning", "warden_spawn_tracker", "spawn_armor_trims",
                "serverpack", "debugconfig",
            )
            // The rules of the game for everyone (keepInventory, pvp, mobGriefing…), the difficulty, game
            // modes, the world border (it hurts players outside), chunks kept loaded, the tick rate,
            // spawn points, datapacks.
            forbid(
                Reason.WORLD,
                "gamerule", "difficulty", "defaultgamemode", "gamemode", "worldborder", "forceload", "tick",
                "setworldspawn", "spawnpoint", "datapack",
            )
            // Functions and schedules run commands this policy never sees.
            forbid(Reason.CODE, "function", "schedule")
            // Blocks: filling, placing, cloning, biomes, structures and features, block entity data.
            // No command writes blocks: nothing could restore them.
            forbid(Reason.BLOCKS, "fill", "fillbiome", "setblock", "clone", "place", "data")
            // Items and progress: inventories and containers (`item`, `loot`), gifts outside give_item's
            // checks, enchantments, experience levels, advancements, recipes (`recipe give @a *` unlocks
            // every recipe for good, when the Narrator is there to slow progress down, not to hand it out).
            forbid(Reason.ITEMS, "clear", "item", "loot", "enchant", "xp", "experience", "advancement", "recipe")
            forbid(Reason.ITEMS, "give", instead = "use the give_item tool")
            // A player's own offering to the Narrator (`/offer`, M4 §7.2): no command makes a player offer (M4-15).
            forbid(Reason.ITEMS, "offer")
            // Players and creatures, outside the protection, the no-piling-on rule, the ledgers and the
            // caps: killing, damaging, tags (`narrator2` marks the Narrator's creatures), teams (friendly
            // fire: PvP is never forced), mounts, view and camera, screen shaders.
            forbid(Reason.ENTITIES, "kill", "damage", "spreadplayers", "ride", "tag", "team", "rotate", "spectate", "posteffect")
            forbid(Reason.ENTITIES, "summon", instead = "use the spawn tool")
            forbid(Reason.ENTITIES, "tp", "teleport", instead = "use the teleport tool")
            forbid(Reason.ENTITIES, "effect", instead = "use apply_effect or remove_effect")
            forbid(Reason.ENTITIES, "attribute", instead = "use set_attribute or reset_attribute")
            // Speech and displays outside the speech tools; `tellraw` and `dialog` can also make a click
            // run a command. Boss bars come with challenges (M4).
            forbid(Reason.SPEECH, "say", "tellraw", "tell", "msg", "w", "me", "teammsg", "tm", "title", "dialog", "bossbar", "pray")
            // What a tool with its own limits already does. Particles and sounds would go without the
            // tools' caps: `particle … 2000000000 … force` brings down every client in sight.
            forbid(Reason.TOOL, "weather", instead = "use the set_weather tool")
            forbid(Reason.TOOL, "time", instead = "use the set_time tool")
            forbid(Reason.TOOL, "locate", instead = "use the locate tool")
            forbid(Reason.TOOL, "particle", instead = "use the particles tool")
            forbid(Reason.TOOL, "playsound", instead = "use the play_sound tool")
        }

        /**
         * The commands reviewed for the Narrator and allowed: `run_command`'s last resort for what the
         * typed tools do not cover (scores, waypoints, `execute if …` probes, `random value`), and
         * harmless reads. Every other command of the server is forbidden or refused as unreviewed.
         */
        val ALLOWED: Set<String> = setOf(
            "execute", "return", "scoreboard", "trigger", "stopsound", "waypoint",
            "random", "compute", "stopwatch", "swing", "help", "list", "version",
        )

        /** Forbidden branches of other commands, by their path from the root (`execute store`). */
        val FORBIDDEN_BRANCHES: Map<List<String>, Forbidden> = mapOf(
            // Writes a command's result into blocks, entities, storage, scores or boss bars.
            listOf("execute", "store") to Forbidden(Reason.BLOCKS, "run the command without store"),
            // Summons a creature, outside the spawn tool's rules.
            listOf("execute", "summon") to Forbidden(Reason.ENTITIES, "use the spawn tool"),
            // Runs a function.
            listOf("execute", "if", "function") to Forbidden(Reason.CODE),
            listOf("execute", "unless", "function") to Forbidden(Reason.CODE),
            // Tells every player the roll, outside the speech tools; `random value` answers the source alone.
            listOf("random", "roll") to Forbidden(Reason.SPEECH, "use random value (its result reaches you alone)"),
        )

        private val EXTRA = Forbidden(Reason.ADMIN)

        /** The first word of a command at [start] in [input], lowercase, without a leading `/`. */
        private fun commandWord(input: String, start: Int): String {
            val text = input.substring(start.coerceIn(0, input.length)).trimStart().removePrefix("/")
            return text.substringBefore(' ').lowercase()
        }

        /** Every node of a forbidden command's tree, with the name it is refused under (`fill`, `execute store`). */
        private fun <S> owners(root: CommandNode<S>, extras: Set<String>): Map<CommandNode<S>, String> {
            val owners = IdentityHashMap<CommandNode<S>, String>()
            for (name in FORBIDDEN.keys + extras) root.getChild(name)?.let { collect(it, name, owners) }
            for (path in FORBIDDEN_BRANCHES.keys) {
                val node = path.fold<String, CommandNode<S>?>(root) { node, child -> node?.getChild(child) } ?: continue
                collect(node, path.joinToString(" "), owners)
            }
            return owners
        }

        /** Every node of every command's tree, with the command it belongs to: where a redirect lands. */
        private fun <S> commands(root: CommandNode<S>): Map<CommandNode<S>, String> {
            val commands = IdentityHashMap<CommandNode<S>, String>()
            for (command in root.children) collect(command, command.name, commands)
            return commands
        }

        /** Adds [node] and its descendants (redirects are not followed: they lead to other commands). */
        private fun <S> collect(node: CommandNode<S>, name: String, owners: MutableMap<CommandNode<S>, String>) {
            if (owners.putIfAbsent(node, name) != null) return
            node.children.forEach { collect(it, name, owners) }
        }

        private fun message(owner: String, extras: Set<String>): String {
            val forbidden = FORBIDDEN_BRANCHES[owner.split(' ')] ?: FORBIDDEN[owner] ?: EXTRA
            val why = if (owner !in FORBIDDEN && owner in extras) "the operators forbid it (safety.extraForbiddenCommands)" else forbidden.reason.text
            return "`$owner` is forbidden: $why" + (forbidden.instead?.let { "; $it instead." } ?: ".")
        }

        private fun unreviewedMessage(command: String): String =
            "`$command` is forbidden: only the commands reviewed for the Narrator run, and this one was not " +
                "(the operators can allow it with safety.extraAllowedCommands); command_help with no command lists those run_command accepts."
    }
}
