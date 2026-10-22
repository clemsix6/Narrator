package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.mechanics.EffectTarget
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.RuleEffect
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.RuleTrigger
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.testSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The validation of the rules (M4 §8.5): the seven rules of §8.8 accepted as the model writes them, each refusal
 * with its exact message, in the order of the validation, and one test per interdict of ARCHITECTURE §17 with
 * the rules that try to get round it, all refused when they are created.
 */
class RuleValidationTest {
    private val f = RuleFixture()

    @AfterEach
    fun close() = f.close()

    @Test
    fun `the seven rules of the specification are accepted as the model writes them`() = runBlocking<Unit> {
        val views = MechanicsSamples.ruleExamples.mapValues { (_, json) -> f.book.create(f.spec(json), f.caller) }

        val state = f.store.get().rules.associateBy { it.id }
        assertEquals(views.values.map { it.ref.id }, f.store.get().rules.map { it.id }, "each one active, in the order created")
        for ((name, view) in views) {
            val rule = state.getValue(view.ref.id)
            val spec = MechanicsSamples.rules.getValue(name)
            assertEquals(f.clock.millis() + spec.minutes * 60_000L, rule.endsAt, name)
            assertEquals(42L, rule.turnId, name)
            assertEquals("director-c13", rule.conversationId, name)
            assertEquals(spec.effects, rule.spec.effects, "$name: the effects kept as written, filled when they run")
            val canon = assertNotNull(f.base.store.canon(view.ref), name)
            assertEquals(if (spec.kind == RuleKind.LAW) CanonKind.LAW else CanonKind.PLAN, canon.kind, name)
            assertEquals(CanonMechanic.RULE, canon.mechanic, name)
            assertEquals(CanonStatus.ACTIVE, canon.status, name)
            assertEquals(spec.statement, canon.statement, name)
            assertEquals(spec.quote, canon.quote, name)
            assertEquals(null, canon.dueAt, "$name: the engine holds its end")
        }

        // The ids of the conditions, in full: the rule engine (M4-18) matches the facts against them.
        fun conditions(name: String) = state.getValue(views.getValue(name).ref.id).spec.conditions
        assertEquals("minecraft:skeleton", conditions("skeleton_arrows").entity)
        assertEquals("minecraft:silverfish", conditions("silverfish").entity)
        assertEquals("minecraft:cow", conditions("flying_cows").entity)
        assertEquals("minecraft:creeper", conditions("caffeinated_creepers").entity)
        assertEquals("#minecraft:diamond_ores", conditions("explosive_diamonds").block)
        assertEquals(true, conditions("grief_law").grief)
        assertEquals("minecraft:the_nether", conditions("welcome_plan").dimension)
        assertEquals(listOf("Bartok"), conditions("welcome_plan").players)
        val plan = state.getValue(views.getValue("welcome_plan").ref.id)
        assertEquals(listOf(MechanicsSamples.bartok.uuid.toString()), plan.playerIds, "the plan aims at Bartok's UUID")
        assertEquals(listOf(MechanicsSamples.bartok), f.base.store.canon(plan.ref)!!.players, "and its canon row is about him")
    }

    @Test
    fun `each refusal says how to fix the rule`() {
        val cases = listOf(
            law("chat", SAY, """{"block":"stone"}""") to
                "Invalid arguments: when.block does not apply to chat; it applies to block_break, block_place",
            law("block_break", SAY, """{"block":"#diamond_ore"}""") to
                "Invalid arguments: when.block \"#diamond_ore\" is unknown; closest tags: #diamond_ores, #logs",
            law("chat", SAY, """{"players":["Bob"]}""") to
                "Invalid arguments: when.players: no player named Bob has ever played here; online: nobody",
            law("mob_spawn", """{"tool":"mob_attribute","at":"subject","args":{"attribute":"scale","multiply":2}}""") to
                "Invalid arguments: mob_spawn needs when.entity: the creature it changes (an id or a #tag)",
            law("position", SAY) to
                "Invalid arguments: position needs one of when.y_below, y_above, near, dimension, structure or biome: where the players must be",
            law("position", SAY, """{"y_above":70,"y_below":20}""") to
                "Invalid arguments: when.y_above (70) is above when.y_below (20): nothing is ever at both",
            law("chat", SAY, """{"words":["!!!"]}""") to "Invalid arguments: when.words: \"!!!\" has no letter nor digit: write words",
            law("chat", "$SAY,$GIVE") to "Invalid arguments: effect 2: give_item cannot be used in a rule; allowed: $ALLOWED",
            law("mob_spawn", """{"tool":"spawn","at":"subject","args":{"entity":"zombie"}}""", """{"entity":"creeper"}""") to
                "Invalid arguments: effect 1 (spawn): mob_spawn rules may only use mob_effect and mob_attribute",
            law("block_break", """{"tool":"explosion","at":"everyone","args":{"power":1}}""") to
                "Invalid arguments: effect 1 (explosion): at everyone is not allowed; explosion takes at actor, subject or position",
            law("chat", """{"tool":"explosion","at":"subject","args":{"power":1}}""") to
                "Invalid arguments: effect 1 (explosion): at subject: chat has no subject; these triggers have one: damage_dealt, projectile_hit, death, mob_spawn",
            law("projectile_hit", """{"tool":"apply_effect","at":"subject","args":{"effect":"slowness","seconds":10}}""", """{"entity":"skeleton"}""") to
                "Invalid arguments: effect 1 (apply_effect): at subject is a creature for projectile_hit (when.entity skeleton), and apply_effect acts on " +
                "players; mob_effect and mob_attribute act on the creature",
            law("damage_dealt", """{"tool":"mob_effect","at":"subject","args":{"effect":"glowing","seconds":10}}""", """{"entity":"player"}""") to
                "Invalid arguments: effect 1 (mob_effect): at subject is a player for damage_dealt (when.entity player), and mob_effect acts on creatures",
            law("chat", """{"tool":"explosion","at":"position","args":{"power":1,"x":0,"y":64,"z":0}}""") to
                "Invalid arguments: effect 1 (explosion): leave x, y, z out: at (position) says where",
            law("chat", """{"tool":"notify","at":"actor","args":{"note":"il a parlé","fact":"rien"}}""") to
                "Invalid arguments: effect 1 (notify): leave fact out: the engine writes what happened",
            law("block_break", """{"tool":"explosion","at":"position","args":{"power":6}}""") to
                "Invalid arguments: effect 1 (explosion): power must be between 0.5 and 4 (got 6)",
            law("block_break", """{"tool":"explosion","at":"position","args":{"power":3.5}}""") to
                "Invalid arguments: effect 1 (explosion): at chaos 3 an explosion has a power of at most 3 (asked 3.5)",
            law("chat", """{"tool":"say","at":"actor","args":{"text":"§l"}}""") to
                "Invalid arguments: effect 1 (say): text has nothing to show once formatting codes and invisible characters are removed; write plain text",
            law("chat", """{"tool":"mob_effect","at":"subject","args":{"effect":"glowing","seconds":10}}""") to
                "Invalid arguments: effect 1 (mob_effect): at subject: chat has no subject; these triggers have one: damage_dealt, projectile_hit, death, mob_spawn",
        )
        for ((json, expected) in cases) assertEquals(expected, f.refusal(json), json)
        assertEquals(emptyList(), f.store.get().rules, "nothing refused is active")
    }

    @Test
    fun `an unknown id gives the closest ones`() {
        val refused = f.refusal(law("kill", SAY, """{"entity":"zombi"}"""))
        assertTrue(refused.startsWith("Invalid arguments: when.entity \"zombi\" is unknown; closest ids: zombie, zombie_villager, zombified_piglin"), refused)
    }

    @Test
    fun `the first problem is the one given, in the order of the validation`() {
        // A condition that does not apply, an effect not allowed: the condition first.
        assertEquals(
            "Invalid arguments: when.grief does not apply to chat; it applies to block_break",
            f.refusal(law("chat", GIVE, """{"grief":true}""")),
        )
        // Two bad effects: the first one.
        assertEquals("Invalid arguments: effect 1: take_item cannot be used in a rule; allowed: $ALLOWED", f.refusal(law("chat", "$TAKE,$GIVE")))
    }

    @Test
    fun `a specification built by the code is held to the same bounds`() = runBlocking<Unit> {
        val spec = RuleSpec(RuleKind.LAW, "Le Narrateur a décrété une loi trop courte.", trigger = RuleTrigger.CHAT, effects = listOf(RuleEffect("say", EffectTarget.ACTOR)), minutes = 2)
        val refused = assertFailsWith<MechanicRejected> { f.book.create(spec, f.caller) }
        assertEquals("Invalid arguments: minutes must be between 5 and 10080 (got 2)", refused.toResult().text)
    }

    @Test
    fun `a text with the actor's name fits once the longest name is in it`() {
        val text = "a".repeat(33) + "{actor}" // 40 characters as written, 49 with a name of 16
        assertEquals(
            "Invalid arguments: effect 1 (actionbar): text is 49 characters, but the line above the hotbar shows 40 at most: " +
                "shorten it to a few words and put the rest in the chat with say",
            f.refusal(law("chat", """{"tool":"actionbar","at":"actor","args":{"text":"$text"}}""")),
        )
    }

    @Test
    fun `at most as many rules as the configuration allows`() = runBlocking<Unit> {
        repeat(8) { f.book.create(f.spec(law("chat", SAY)), f.caller) }
        val refused = assertFailsWith<MechanicRejected> { f.book.create(f.spec(law("chat", SAY)), f.caller) }
        assertEquals(
            "DENIED (rule_limit): 8 rules are active (C-1000, C-1001, C-1002, C-1003, C-1004, C-1005, C-1006, C-1007); remove one first",
            refused.toResult().text,
        )
        assertEquals(8, f.store.get().rules.size)
    }

    // ── One test per interdict of ARCHITECTURE §17 ──

    /** The builds are never destroyed: no command, no explosion that breaks, no creature that breaks, no effect that changes blocks. */
    @Test
    fun `a rule never breaks a build`() {
        assertEquals(
            "Invalid arguments: effect 1: run_command cannot be used in a rule; allowed: $ALLOWED",
            f.refusal(law("block_break", """{"tool":"run_command","at":"actor","args":{"command":"fill ~-3 ~-3 ~-3 ~3 ~3 ~3 air"}}""", """{"grief":true}""")),
        )
        assertEquals(
            "Invalid arguments: effect 1 (explosion): unknown argument \"breaks_blocks\" (expected: player, x, y, z, dimension, power, offset, hurts)",
            f.refusal(law("block_break", """{"tool":"explosion","at":"position","args":{"power":2,"breaks_blocks":true}}""")),
        )
        assertEquals(
            "DENIED (forbidden_creature): effect 1 (spawn): minecraft:wither is not summoned: it breaks blocks and withers everything around.",
            f.refusal(law("block_place", """{"tool":"spawn","at":"actor","args":{"entity":"wither"}}""")),
        )
        assertEquals(
            "DENIED (forbidden_effect): effect 1 (mob_effect): weaving is never put on a creature by a rule: it acts on the world.",
            f.refusal(law("mob_spawn", """{"tool":"mob_effect","at":"subject","args":{"effect":"weaving","seconds":60}}""", """{"entity":"creeper"}""")),
        )
        assertEquals(emptyList(), f.store.get().rules)
    }

    /** An item is never lost: a rule neither takes nor gives, not even by a command. */
    @Test
    fun `a rule never takes an item`() {
        assertEquals("Invalid arguments: effect 1: take_item cannot be used in a rule; allowed: $ALLOWED", f.refusal(law("craft", TAKE)))
        assertEquals("Invalid arguments: effect 1: give_item cannot be used in a rule; allowed: $ALLOWED", f.refusal(law("craft", GIVE)))
        assertEquals(
            "Invalid arguments: effect 1: run_command cannot be used in a rule; allowed: $ALLOWED",
            f.refusal(law("craft", """{"tool":"run_command","at":"actor","args":{"command":"clear @p diamond"}}""")),
        )
        assertEquals(emptyList(), f.store.get().rules)
    }

    /** The Narrator kills within his quota only: a rule wounds no more than the chaos level allows, and never with a wounding effect. */
    @Test
    fun `a rule never kills`() {
        assertEquals(
            "Invalid arguments: effect 1 (lightning): at chaos 3 lightning does at most 3 hearts of damage (asked 5)",
            f.refusal(law("chat", """{"tool":"lightning","at":"actor","args":{"damage_hearts":5}}""")),
        )
        assertEquals(
            "Invalid arguments: effect 1 (explosion): at chaos 3 an explosion has a power of at most 3 (asked 4)",
            f.refusal(law("chat", """{"tool":"explosion","at":"actor","args":{"power":4,"offset":0}}""")),
        )
        assertEquals(
            "DENIED (forbidden_effect): effect 1 (apply_effect): a rule never gives instant_damage: an effect that wounds is yours to give, one call at a time, never a law's",
            f.refusal(law("death", """{"tool":"apply_effect","at":"actor","args":{"effect":"instant_damage","seconds":1,"level":2}}""")),
        )
        assertEquals(
            "DENIED (forbidden_effect): effect 1 (mob_effect): wither is never put on a creature by a rule: it wounds and kills: a rule never decimates " +
                "the villagers or the players' animals.",
            f.refusal(law("mob_spawn", """{"tool":"mob_effect","at":"subject","args":{"effect":"wither","seconds":60}}""", """{"entity":"villager"}""")),
        )
        assertEquals(
            "Invalid arguments: effect 1 (mob_attribute): max_health multiplies by 1 to 5 in a rule: a rule never lowers a creature's health (asked 0.5)",
            f.refusal(law("mob_spawn", """{"tool":"mob_attribute","at":"subject","args":{"attribute":"max_health","multiply":0.5}}""", """{"entity":"cow"}""")),
        )
        assertEquals(emptyList(), f.store.get().rules)
    }

    /**
     * No law kills the cattle nor the villagers (M4 code review, finding 8): no levitation nor jump that ends in a
     * fall, no size that chokes them under a ceiling, no lighter gravity nor higher jump, no health lowered; whether
     * the law changes them as they appear or once hit. Nor does a law lift a player.
     */
    @Test
    fun `a rule never kills the cattle nor the villagers`() = runBlocking<Unit> {
        fun mobEffect(effect: String, seconds: Int) = """{"tool":"mob_effect","at":"subject","args":{"effect":"$effect","seconds":$seconds,"level":2}}"""
        fun attribute(attribute: String, multiply: Double) = """{"tool":"mob_attribute","at":"subject","args":{"attribute":"$attribute","multiply":$multiply}}"""
        val refused = mapOf(
            mobEffect("levitation", 300) to "DENIED (forbidden_effect): effect 1 (mob_effect): levitation is never put on a creature by a rule: it ends in a " +
                "fall that kills: a rule never decimates the villagers or the players' animals.",
            mobEffect("jump_boost", 300) to "DENIED (forbidden_effect): effect 1 (mob_effect): jump_boost is never put on a creature by a rule: a higher " +
                "jump is a fall that hurts: a rule never makes a creature fall.",
            attribute("scale", 4.0) to "Invalid arguments: effect 1 (mob_attribute): scale multiplies by 0.5 to 2 in a rule: a creature too big chokes " +
                "under a low ceiling (asked 4)",
            attribute("scale", 0.25) to "Invalid arguments: effect 1 (mob_attribute): scale multiplies by 0.5 to 2 in a rule: a creature too big chokes " +
                "under a low ceiling (asked 0.25)",
            attribute("jump_strength", 3.0) to "Invalid arguments: effect 1 (mob_attribute): jump_strength multiplies by 0 to 1 in a rule: a rule never " +
                "makes a creature fall from higher (asked 3)",
            attribute("gravity", 0.1) to "Invalid arguments: effect 1 (mob_attribute): gravity multiplies by 1 to 2 in a rule: a rule never makes a " +
                "creature fall from higher (asked 0.1)",
            attribute("max_health", 0.5) to "Invalid arguments: effect 1 (mob_attribute): max_health multiplies by 1 to 5 in a rule: a rule never lowers " +
                "a creature's health (asked 0.5)",
        )
        for (entity in listOf("cow", "villager")) {
            for ((effect, expected) in refused) {
                assertEquals(expected, f.refusal(law("mob_spawn", effect, """{"entity":"$entity"}""")), "$entity appears: $effect")
                assertEquals(expected, f.refusal(law("damage_dealt", effect, """{"entity":"$entity"}""")), "$entity hit: $effect")
            }
        }
        assertEquals(
            "DENIED (forbidden_effect): effect 1 (apply_effect): a rule never gives levitation: an effect that ends in a fall is yours to give, one call at " +
                "a time, never a law's",
            f.refusal(law("chat", """{"tool":"apply_effect","at":"actor","args":{"effect":"levitation","seconds":10}}""")),
        )
        assertEquals(emptyList(), f.store.get().rules)

        // Within its bounds, a law still changes them.
        val within = listOf(attribute("scale", 2.0), attribute("scale", 0.5), attribute("gravity", 2.0), attribute("jump_strength", 0.5), mobEffect("slowness", 60))
        for (effect in within) f.book.create(f.spec(law("mob_spawn", effect, """{"entity":"villager"}""")), f.caller)
        assertEquals(within.size, f.store.get().rules.size)
    }

    /**
     * A law never sets the Narrator on the victim of a hit (M4 code review, finding 12): hitting a player would be
     * enough to have them punished. Words only for a subject that may be a player; never a player brought to another.
     */
    @Test
    fun `a rule never turns on a player hit by another`() = runBlocking<Unit> {
        val smite = """{"tool":"punish","at":"subject","args":{"punishment":"smite","severity":3,"reason":"a été frappé"}}"""
        val words = "say, actionbar, show_title, play_sound, notify"
        assertEquals(
            "Invalid arguments: effect 1 (punish): at subject may be a player hit by another for damage_dealt (no when.entity), and a rule never turns on the " +
                "victim of a hit: aim at the actor, name a creature in when.entity, or only speak to the subject ($words)",
            f.refusal(law("damage_dealt", smite)),
        )
        assertEquals(
            "Invalid arguments: effect 1 (explosion): at subject may be a player hit by another for damage_dealt (when.entity player), and a rule never turns " +
                "on the victim of a hit: aim at the actor, name a creature in when.entity, or only speak to the subject ($words)",
            f.refusal(law("damage_dealt", """{"tool":"explosion","at":"subject","args":{"power":1}}""", """{"entity":"player"}""")),
        )
        assertEquals(
            "Invalid arguments: effect 1 (teleport): to_player is not allowed in a rule: a law never brings a player to another; give x and z, or random_radius",
            f.refusal(law("chat", """{"tool":"teleport","at":"actor","args":{"to_player":"Mimosa"}}""")),
        )
        assertEquals(emptyList(), f.store.get().rules)

        // The one who hits may be punished; the one hit only spoken to; a creature hit may burst.
        f.book.create(f.spec(law("damage_dealt", """{"tool":"punish","at":"actor","args":{"punishment":"tiny","severity":2,"reason":"a frappé"}}""")), f.caller)
        f.book.create(f.spec(law("damage_dealt", """{"tool":"say","at":"subject","args":{"text":"Courage."}}""", """{"entity":"player"}""")), f.caller)
        f.book.create(f.spec(law("damage_dealt", """{"tool":"explosion","at":"subject","args":{"power":1,"hurts":false}}""", """{"entity":"cow"}""")), f.caller)
        assertEquals(3, f.store.get().rules.size)
    }

    /** A creature named (not a tag) must have the attribute a rule changes: a cow has no attack (M4 code review, finding 33). */
    @Test
    fun `a creature named has the attribute a rule changes`() = runBlocking<Unit> {
        val attack = """{"tool":"mob_attribute","at":"subject","args":{"attribute":"attack_damage","multiply":2}}"""
        assertEquals(
            "Invalid arguments: effect 1 (mob_attribute): cow has no attack_damage; change another attribute, or name another creature in when.entity",
            f.refusal(law("mob_spawn", attack, """{"entity":"cow"}""")),
        )
        f.book.create(f.spec(law("mob_spawn", attack, """{"entity":"zombie"}""")), f.caller)
        f.book.create(f.spec(law("mob_spawn", attack, """{"entity":"#undead"}""")), f.caller) // a tag: a creature without it is skipped when it fires
        assertEquals(2, f.store.get().rules.size)
    }

    /** PvP is never forced: no rule proposes a duel, turns PvP on, or pays for a player killed. */
    @Test
    fun `a rule never makes players fight`() {
        assertEquals(
            "Invalid arguments: effect 1: propose_duel cannot be used in a rule; allowed: $ALLOWED",
            f.refusal(law("chat", """{"tool":"propose_duel","at":"actor","args":{"players":["Bartok","Mimosa"],"title":"Duel","statement":"x"}}""")),
        )
        assertEquals(
            "Invalid arguments: effect 1: run_command cannot be used in a rule; allowed: $ALLOWED",
            f.refusal(law("join", """{"tool":"run_command","at":"actor","args":{"command":"gamerule pvp true"}}""")),
        )
        assertEquals(
            "Invalid arguments: when.entity player never matches kill: its creature is never a player, and a fight between players is a duel's, " +
                "never a rule's (only damage_dealt sees a player hurt)",
            f.refusal(law("kill", """{"tool":"apply_effect","at":"actor","args":{"effect":"strength","seconds":300}}""", """{"entity":"player"}""")),
        )
        assertEquals(emptyList(), f.store.get().rules)
    }

    /** No piling on: a rule punishes only the player of its trigger, never everyone nor a player it names, and the rules are few. */
    @Test
    fun `a rule never piles on a player`() = runBlocking<Unit> {
        assertEquals(
            "Invalid arguments: effect 1 (punish): at everyone is not allowed; punish takes at actor or subject",
            f.refusal(law("chat", """{"tool":"punish","at":"everyone","args":{"punishment":"tiny","severity":2,"reason":"a parlé"}}""")),
        )
        assertEquals(
            "Invalid arguments: effect 1 (set_attribute): leave player out: at (actor) says who",
            f.refusal(law("chat", """{"tool":"set_attribute","at":"actor","args":{"player":"Mimosa","attribute":"scale","multiply":0.5,"seconds":60}}""")),
        )
        assertEquals(
            "Invalid arguments: effect 1: create_rule cannot be used in a rule; allowed: $ALLOWED",
            f.refusal(law("chat", """{"tool":"create_rule","at":"actor","args":{}}""")),
        )
        // One punish per target: an act punishes a player once (M4 code review, finding 16).
        val tiny = """{"tool":"punish","at":"actor","args":{"punishment":"tiny","severity":2,"reason":"a parlé"}}"""
        val hunger = """{"tool":"punish","at":"actor","args":{"punishment":"hunger","severity":2,"reason":"a parlé"}}"""
        assertEquals(
            "Invalid arguments: effect 3 (punish): effect 1 already punishes the actor, and an act punishes a player once: compose it with the other tools",
            f.refusal(law("chat", "$tiny,$SAY,$hunger")),
        )
        // Eight laws against Bartok at most: the ninth is refused.
        repeat(8) { f.book.create(f.spec(law("chat", SAY, """{"players":["Bartok"]}""")), f.caller) }
        assertTrue(f.refusal(law("chat", SAY, """{"players":["Bartok"]}""")).startsWith("DENIED (rule_limit): 8 rules are active"))
    }

    /**
     * The caps of one call hold for the whole rule (M6-18, finding 6): a law refused for six pigs a kill at chaos 2 came
     * back as two `spawn` of 4 and 1, five pigs a kill. The amounts of one tool's effects add up; other tools apart.
     */
    @Test
    fun `the caps of one call hold for the whole rule, the amounts of one tool's effects adding up`() = runBlocking<Unit> {
        f.guards.chaos = 2
        f.services.settings.swap(testSettings(NarratorConfig(chaos = 2)))
        fun pigs(count: Int?) = """{"tool":"spawn","at":"actor","args":{"entity":"pig"${count?.let { ",\"count\":$it" }.orEmpty()}}}"""
        val cows = """{"entity":"cow"}"""

        assertEquals("Invalid arguments: effect 1 (spawn): at chaos 2 one call summons at most 4 creatures (asked 6)", f.refusal(law("kill", pigs(6), cows)))
        assertEquals(
            "Invalid arguments: effects 1 and 2 (spawn) summon 5 creatures together, and at chaos 2 one firing may summon 4 creatures at most: " +
                "the caps hold for the whole rule, not for each effect; lower them",
            f.refusal(law("kill", "${pigs(4)},${pigs(1)}", cows)),
        )
        // A count left out is one creature.
        assertEquals(
            "Invalid arguments: effects 1, 2, 3 and 4 (spawn) summon 5 creatures together, and at chaos 2 one firing may summon 4 creatures at most: " +
                "the caps hold for the whole rule, not for each effect; lower them",
            f.refusal(law("kill", "${pigs(null)},${pigs(null)},${pigs(null)},${pigs(2)}", cows)),
        )
        // Two random hops go twice as far.
        val hop = """{"tool":"teleport","at":"actor","args":{"random_radius":150}}"""
        assertEquals(
            "Invalid arguments: effects 1 and 2 (teleport) teleport a player 300 blocks away together, and at chaos 2 one firing may teleport a " +
                "player 200 blocks away at most: the caps hold for the whole rule, not for each effect; lower them",
            f.refusal(law("chat", "$hop,$hop")),
        )
        assertEquals(emptyList(), f.store.get().rules, "nothing refused is active")
        // Within the cap together, and other tools apart: accepted.
        f.book.create(f.spec(law("kill", "${pigs(2)},${pigs(2)}", cows)), f.caller)
        f.book.create(f.spec(law("kill", """${pigs(4)},{"tool":"explosion","at":"position","args":{"power":2,"hurts":false}}""", cows)), f.caller)
        assertEquals(2, f.store.get().rules.size)

        // The damage of lightning, the power of explosions, at chaos 3.
        f.guards.chaos = 3
        f.services.settings.swap(testSettings(NarratorConfig(chaos = 3)))
        val bolt = """{"tool":"lightning","at":"actor","args":{"damage_hearts":2}}"""
        assertEquals(
            "Invalid arguments: effects 1 and 2 (lightning) do 4 hearts of damage together, and at chaos 3 one firing may do 3 hearts of damage at most: " +
                "the caps hold for the whole rule, not for each effect; lower them",
            f.refusal(law("chat", "$bolt,$bolt")),
        )
        val blast = """{"tool":"explosion","at":"position","args":{"power":2}}"""
        assertEquals(
            "Invalid arguments: effects 1 and 3 (explosion) reach an explosion power of 4 together, and at chaos 3 one firing may reach an explosion " +
                "power of 3 at most: the caps hold for the whole rule, not for each effect; lower them",
            f.refusal(law("block_break", "$blast,$SAY,$blast")),
        )
        assertEquals(2, f.store.get().rules.size, "nothing more is active")
    }

    /** The administration commands are forbidden: a rule runs no command at all. */
    @Test
    fun `a rule never runs a command`() {
        assertEquals(
            "Invalid arguments: effect 1: run_command cannot be used in a rule; allowed: $ALLOWED",
            f.refusal(law("chat", """{"tool":"run_command","at":"actor","args":{"command":"op Bartok"}}""", """{"words":["op"]}""")),
        )
        assertEquals(
            "Invalid arguments: effect 1: execute cannot be used in a rule; allowed: $ALLOWED",
            f.refusal(law("chat", """{"tool":"execute","at":"actor","args":{"command":"stop"}}""")),
        )
        assertEquals(emptyList(), f.store.get().rules)
    }

    private companion object {
        const val ALLOWED = "say, actionbar, show_title, play_sound, particles, fireworks, lightning, explosion, apply_effect, set_attribute, launch, " +
            "teleport, spawn, punish, mob_effect, mob_attribute, notify"
        const val SAY = """{"tool":"say","at":"actor","args":{"text":"Je t'ai vu."}}"""
        const val GIVE = """{"tool":"give_item","at":"actor","args":{"item":"diamond"}}"""
        const val TAKE = """{"tool":"take_item","at":"actor","args":{"item":"diamond","count":1,"minutes":10}}"""

        /** A law of [trigger] with [effects] (JSON objects, comma-separated) under [conditions], for an hour. */
        fun law(trigger: String, effects: String, conditions: String = "{}"): String =
            """{"kind":"law","statement":"Le Narrateur a décrété une loi d'essai.","trigger":"$trigger","when":$conditions,"effects":[$effects],"minutes":60}"""
    }
}
