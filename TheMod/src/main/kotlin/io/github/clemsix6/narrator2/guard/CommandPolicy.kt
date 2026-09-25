package io.github.clemsix6.narrator2.guard

import com.mojang.brigadier.ParseResults
import net.minecraft.commands.CommandSourceStack

/**
 * What `run_command` may run (M1_M2 §10.6): the command is parsed first, then the heads of all its
 * segments (behind `execute … run` too) are checked against the forbidden roots, the built-in list plus
 * [extra] (`safety.extraForbiddenCommands`, read at each check: `/narrator reload` may change it).
 *
 * Written by T02 as the contract between T09a (consumer) and T09b (owner). Provisional body: every
 * command is refused. T09b writes it.
 */
class CommandPolicy(@Suppress("unused") private val extra: () -> List<String>) {
    /** Whether the parsed command may run. */
    @Suppress("UNUSED_PARAMETER")
    fun check(parse: ParseResults<CommandSourceStack>): Verdict =
        Verdict.Denied(Rules.FORBIDDEN_COMMAND, "commands are not allowed until the engine's rules are in place.")
}
