package io.github.clemsix6.narrator2.mechanics.punish

import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.Caps
import io.github.clemsix6.narrator2.guard.Peril
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.tool.action.ApplyEffectArgs
import io.github.clemsix6.narrator2.tool.action.EquipmentSpec
import io.github.clemsix6.narrator2.tool.action.LaunchArgs
import io.github.clemsix6.narrator2.tool.action.LaunchDirection
import io.github.clemsix6.narrator2.tool.action.LightningArgs
import io.github.clemsix6.narrator2.tool.action.ParticleKey
import io.github.clemsix6.narrator2.tool.action.ParticlesArgs
import io.github.clemsix6.narrator2.tool.action.SetAttributeArgs
import io.github.clemsix6.narrator2.tool.action.SpawnArgs
import io.github.clemsix6.narrator2.tool.action.WornItem
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The catalogue of `punish` (M4 §6.3): each punishment × severity 1 to 5 × chaos 1 to 5, its steps and their exact
 * values, the caps of the chaos level said; a severity beyond the chaos level refused. The expectations are the
 * design's table, written out again here; a few whole plans are spelled out at the end.
 */
class PunishmentCatalogTest {
    private val player = "Bartok"

    /** What the design's table says of [id] at severity [s] within [caps], step by step. */
    private fun expected(id: PunishmentId, s: Int, caps: Caps): PunishmentPlan {
        val notes = mutableListOf<String>()
        fun seconds(perSeverity: Int) = (perSeverity * s).let { if (it > caps.harmfulSeconds) caps.harmfulSeconds.also { notes += "duration capped at ${it}s by the chaos level" } else it }
        fun level(effect: String, asked: Int) = if (asked > caps.harmfulLevel) caps.harmfulLevel.also { notes += "$effect capped at level ${ROMAN[it - 1]} by the chaos level" } else asked
        fun count(what: String, asked: Int) = if (asked > caps.spawnsPerAction) caps.spawnsPerAction.also { notes += "$what capped at $it by the chaos level" } else asked
        fun attribute(key: AttributeKey, factor: Double, seconds: Int) = Step.Attribute(SetAttributeArgs(player, key, multiply = factor, seconds = seconds))
        fun effect(id: String, seconds: Int, level: Int) = Step.Effect(ApplyEffectArgs(listOf(player), id, seconds = seconds, level = level))
        fun particles(key: ParticleKey) = Step.Particles(ParticlesArgs(player = player, particle = key, count = 40))
        val category = PunishmentCategory.of(id)

        fun plan(seconds: Int, lasting: Boolean, peril: Peril?, primitive: Primitive?, steps: List<Step>, strikes: Set<PunishmentCategory> = setOf(category)) =
            PunishmentPlan(id, s, category, strikes, seconds, lasting, peril, primitive, steps, notes.toList())

        return when (id) {
            PunishmentId.TINY -> seconds(60).let { plan(it, true, null, null, listOf(attribute(AttributeKey.SCALE, listOf(0.6, 0.5, 0.4, 0.33, 0.25)[s - 1], it), particles(ParticleKey.POOF))) }
            PunishmentId.MOON_GRAVITY -> seconds(60).let { plan(it, true, Peril.FALL, null, listOf(attribute(AttributeKey.GRAVITY, listOf(0.5, 0.4, 0.3, 0.2, 0.15)[s - 1], it), particles(ParticleKey.END_ROD))) }
            PunishmentId.SLOW_HANDS -> seconds(60).let { d ->
                val speed = attribute(AttributeKey.BLOCK_BREAK_SPEED, listOf(0.6, 0.45, 0.3, 0.2, 0.1)[s - 1], d)
                plan(d, true, null, null, if (s >= 3) listOf(speed, effect("mining_fatigue", d, level("mining_fatigue", s - 2))) else listOf(speed))
            }
            PunishmentId.HEAVY_FEET -> seconds(45).let { d ->
                val slowness = effect("slowness", d, level("slowness", (s + 1) / 2))
                if (s >= 2) {
                    plan(d, true, null, null, listOf(slowness, attribute(AttributeKey.JUMP_STRENGTH, 0.5, d)), setOf(PunishmentCategory.MOVEMENT, PunishmentCategory.GRAVITY))
                } else {
                    plan(d, true, null, null, listOf(slowness))
                }
            }
            PunishmentId.HUNGER -> seconds(30).let { plan(it, true, null, null, listOf(effect("hunger", it, level("hunger", s)))) }
            PunishmentId.BUTTERFINGERS -> plan(30, false, if (s >= 3) Peril.ANY else null, Primitive.Shuffle(hotbar = s >= 3), emptyList())
            PunishmentId.GUESTS -> {
                val zombies = SpawnArgs(
                    "zombie", count = count("zombies", s + 1), near = player, name = "Comité d'accueil", target = true,
                    equipment = EquipmentSpec(head = WornItem("carved_pumpkin")), lifetimeMinutes = 2, baby = s <= 2,
                )
                plan(120, true, Peril.ANY, null, listOf(Step.Spawn(zombies)))
            }
            PunishmentId.SLIME_RAIN -> {
                val slimes = SpawnArgs("slime", count = count("slimes", 2 + 2 * s), near = player, distance = 4, lifetimeMinutes = 1, height = 12)
                plan(60, true, Peril.ANY, null, listOf(Step.Spawn(slimes, tiny = true)))
            }
            PunishmentId.SKYWARD -> {
                val asked = listOf(0.9, 1.2, 1.5, 1.8, 2.1)[s - 1]
                val strength = if (asked > caps.launchStrength) caps.launchStrength.also { notes += "strength capped at ${plain(it)} by the chaos level" } else asked
                plan(15, true, Peril.FALL, null, listOf(Step.Launch(LaunchArgs(player, LaunchDirection.UP, strength))))
            }
            // Any death up there is the Narrator's, not only the fall (M4 review, finding 10).
            PunishmentId.TREE_TOP -> plan(60, false, Peril.ANY, Primitive.TreeTop, emptyList())
            PunishmentId.SMITE -> {
                val hearts = if (s > caps.lightningHearts) caps.lightningHearts.also { notes += "damage capped at ${plain(it)} hearts by the chaos level" } else s.toDouble()
                plan(30, false, Peril.ANY, null, listOf(Step.Lightning(LightningArgs(player = player, damageHearts = hearts))))
            }
            PunishmentId.CAGE -> plan(10 * s, true, Peril.ANY, Primitive.Trap(TrapShape.CAGE), emptyList())
            PunishmentId.WEB -> plan(10 * s, true, Peril.ANY, Primitive.Trap(TrapShape.WEB), emptyList())
        }
    }

    @Test
    fun `every punishment at every severity and chaos level has its steps and values, and says what the caps bounded`() {
        var plans = 0
        for (chaos in 1..5) {
            val caps = Caps.of(chaos)
            for (id in PunishmentId.entries) {
                for (s in 1..5) {
                    if (s > caps.maxSeverity) {
                        assertEquals(
                            "at chaos $chaos a punishment has a severity of at most ${caps.maxSeverity} (asked $s)",
                            PunishmentCatalog.severityRefusal(s, caps, chaos),
                            "${id.id} at severity $s, chaos $chaos",
                        )
                        continue
                    }
                    assertNull(PunishmentCatalog.severityRefusal(s, caps, chaos), "${id.id} at severity $s, chaos $chaos")
                    val plan = PunishmentCatalog.plan(id, s, caps, player, name = "Comité d'accueil")
                    assertEquals(expected(id, s, caps), plan, "${id.id} at severity $s, chaos $chaos")
                    plans++
                }
            }
        }
        // Severities allowed: 2 at chaos 1, 3 at chaos 2, 4 at chaos 3, 5 at chaos 4 and 5.
        assertEquals(PunishmentId.entries.size * (2 + 3 + 4 + 5 + 5), plans)
    }

    @Test
    fun `every value a plan asks of a tool stays within the caps of its chaos level`() {
        for (chaos in 1..5) {
            val caps = Caps.of(chaos)
            for (id in PunishmentId.entries) for (s in 1..caps.maxSeverity) {
                val plan = PunishmentCatalog.plan(id, s, caps, player)
                val what = "${id.id} at severity $s, chaos $chaos"
                for (step in plan.steps) {
                    when (step) {
                        is Step.Attribute -> {
                            assertTrue(step.args.seconds <= caps.harmfulSeconds, what)
                            assertTrue(step.args.seconds >= 10, "set_attribute lasts 10 s at least: $what")
                        }
                        is Step.Effect -> assertTrue(step.args.seconds <= caps.harmfulSeconds && step.args.level <= caps.harmfulLevel, what)
                        is Step.Spawn -> assertTrue(step.args.count <= caps.spawnsPerAction, what)
                        is Step.Launch -> assertTrue(step.args.strength <= caps.launchStrength, what)
                        is Step.Lightning -> assertTrue(step.args.damageHearts <= caps.lightningHearts, what)
                        is Step.Particles -> Unit
                    }
                }
                assertEquals(PunishmentCategory.of(id), plan.category, what)
                assertTrue(plan.category in plan.strikes, what)
                assertTrue(plan.seconds > 0, what)
                if (plan.steps.isEmpty()) assertTrue(plan.primitive != null, "a punishment does something: $what")
            }
        }
    }

    @Test
    fun `heavy feet also strikes gravity from severity 2, by its jump step`() {
        val caps = Caps.of(3)
        assertEquals(setOf(PunishmentCategory.MOVEMENT), PunishmentCatalog.plan(PunishmentId.HEAVY_FEET, 1, caps, player).strikes)
        assertEquals(setOf(PunishmentCategory.MOVEMENT, PunishmentCategory.GRAVITY), PunishmentCatalog.plan(PunishmentId.HEAVY_FEET, 2, caps, player).strikes)
        assertEquals(setOf(PunishmentCategory.MINING), PunishmentCatalog.plan(PunishmentId.SLOW_HANDS, 4, caps, player).strikes, "fatigue is mining too")
    }

    /** Whole plans, spelled out: the caps said in the words the model reads. */
    @Test
    fun `the caps are said in the model's words`() {
        val one = PunishmentCatalog.plan(PunishmentId.TINY, 2, Caps.of(1), player)
        assertEquals(60, one.seconds)
        assertEquals(listOf("duration capped at 60s by the chaos level"), one.capped)
        assertEquals(listOf(Step.Attribute(SetAttributeArgs(player, AttributeKey.SCALE, multiply = 0.5, seconds = 60)), Step.Particles(ParticlesArgs(player = player, particle = ParticleKey.POOF, count = 40))), one.steps)

        val heavy = PunishmentCatalog.plan(PunishmentId.HEAVY_FEET, 3, Caps.of(2), player)
        assertEquals(listOf("duration capped at 120s by the chaos level", "slowness capped at level I by the chaos level"), heavy.capped)
        assertEquals(
            listOf(
                Step.Effect(ApplyEffectArgs(listOf(player), "slowness", seconds = 120, level = 1)),
                Step.Attribute(SetAttributeArgs(player, AttributeKey.JUMP_STRENGTH, multiply = 0.5, seconds = 120)),
            ),
            heavy.steps,
        )
        assertEquals(listOf("hunger capped at level II by the chaos level"), PunishmentCatalog.plan(PunishmentId.HUNGER, 4, Caps.of(3), player).capped)
        assertEquals(listOf("zombies capped at 2 by the chaos level"), PunishmentCatalog.plan(PunishmentId.GUESTS, 2, Caps.of(1), player).capped)
        assertEquals(listOf("slimes capped at 6 by the chaos level"), PunishmentCatalog.plan(PunishmentId.SLIME_RAIN, 4, Caps.of(3), player).capped)
        assertEquals(listOf("strength capped at 1 by the chaos level"), PunishmentCatalog.plan(PunishmentId.SKYWARD, 2, Caps.of(1), player).capped)
        assertEquals(listOf("damage capped at 3 hearts by the chaos level"), PunishmentCatalog.plan(PunishmentId.SMITE, 4, Caps.of(3), player).capped)

        val cage = PunishmentCatalog.plan(PunishmentId.CAGE, 4, Caps.of(3), player)
        assertEquals(4 * MechanicsLimits.TRAP_SECONDS_PER_SEVERITY, cage.seconds, "a trap lasts 10 s per point of severity, no other cap")
        assertEquals(emptyList(), cage.capped)
        assertEquals(PunishmentCategory.TRAP, cage.category)

        val full = PunishmentCatalog.plan(PunishmentId.SLOW_HANDS, 5, Caps.of(5), player)
        assertEquals(emptyList(), full.capped, "at chaos 5 the full wrath fits")
        assertEquals(300, full.seconds)
        assertEquals(Step.Effect(ApplyEffectArgs(listOf(player), "mining_fatigue", seconds = 300, level = 3)), full.steps[1])
    }

    @Test
    fun `the guests bear their name and a hat, babies when the severity is low`() {
        val low = (PunishmentCatalog.plan(PunishmentId.GUESTS, 1, Caps.of(3), player, name = "Comité").steps.single() as Step.Spawn).args
        assertEquals(Triple(2, true, "Comité"), Triple(low.count, low.baby, low.name))
        assertEquals(EquipmentSpec(head = WornItem("carved_pumpkin")), low.equipment)
        assertTrue(low.target)
        val high = (PunishmentCatalog.plan(PunishmentId.GUESTS, 4, Caps.of(3), player).steps.single() as Step.Spawn).args
        assertEquals(Triple(5, false, null), Triple(high.count, high.baby, high.name))
    }

    @Test
    fun `a severity outside 1 to 5 is no plan`() {
        assertFailsWith<IllegalArgumentException> { PunishmentCatalog.plan(PunishmentId.TINY, 0, Caps.of(3), player) }
        assertFailsWith<IllegalArgumentException> { PunishmentCatalog.plan(PunishmentId.TINY, 6, Caps.of(5), player) }
        assertEquals("at chaos 0 a punishment has a severity of at most 0 (asked 1)", PunishmentCatalog.severityRefusal(1, Caps.of(0), 0))
    }

    // ── The shuffle of butterfingers ────────────────────────────────────────────────────────────

    @Test
    fun `a shuffle moves something and loses nothing`() {
        val items = listOf("diamond", "", "", "stick", "dirt", "", "bread", "torch", "", "")
        repeat(200) { seed ->
            val random = Random(seed)
            val order = Primitives.shuffled(items, { a, b -> a == b }, random::nextInt)!!
            assertEquals(items.sorted(), order.sorted(), "the same items, each once (seed $seed)")
            assertNotEquals(items, order, "something moved (seed $seed)")
            assertTrue(Primitives.moved(items, order) { a, b -> a == b } >= 2, "at least two slots changed (seed $seed)")
        }
    }

    @Test
    fun `nothing to shuffle when every slot holds the same, and a draw that moves nothing falls back on a swap`() {
        assertNull(Primitives.shuffled(List(27) { "" }, { a, b -> a == b }) { 0 }, "an empty inventory")
        assertNull(Primitives.shuffled(List(27) { "cobblestone" }, { a, b -> a == b }) { 0 }, "the same stack everywhere")
        assertNull(Primitives.shuffled(emptyList<String>(), { a, b -> a == b }) { 0 })
        // Draws that always keep each item in place.
        val order = Primitives.shuffled(listOf("", "", "stick"), { a, b -> a == b }) { bound -> bound - 1 }
        assertEquals(listOf("stick", "", ""), order)
    }

    private companion object {
        val ROMAN = listOf("I", "II", "III", "IV", "V")

        fun plain(value: Double): String = if (value == Math.floor(value)) value.toInt().toString() else value.toString()
    }
}
