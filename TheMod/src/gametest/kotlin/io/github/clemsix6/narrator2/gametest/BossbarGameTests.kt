package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.mechanics.BarText
import io.github.clemsix6.narrator2.mechanics.Bossbars
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.BossEvent.BossBarColor
import net.minecraft.world.BossEvent.BossBarOverlay
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.GameType
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-game tests of the boss bars (M4 §5.4, M4-09): a bar shown to its participant alone and updated, which
 * follows a respawn, is taken down when its player leaves and shown again when they come back, comes back from
 * the saved state after a restart, and is taken down when its challenge ends.
 *
 * Each test has its own [Bossbars] and its own players, whose connection records the boss bar packets: what
 * their client shows is those packets played back ([BarClient.view]). The real engine is not wired yet (M4-23):
 * the tests draw the bars from a state of their own, as the engine's part does each second.
 */
class BossbarGameTests {

    @GameTest
    fun aBarIsShownToItsParticipantAloneAndUpdated(helper: GameTestHelper) = withClients(helper, 3) { (bartok, mimosa, josiane) ->
        val bars = Bossbars()
        val ui = ui(helper)
        val challenge = challenge(50, MechanicsSamples.turtleEgg.copy(count = 4), bartok, mimosa)

        bars.render(helper.level.server, state(challenge), ui, NOW)
        val bartokSees = bartok.view().values.single()
        helper.assertEquals(BarText.of(challenge, challenge.participants[0], NOW, ui)!!.name, bartokSees.name, "Bartok's bar")
        helper.assertTrue("0/4" in bartokSees.name && "15:00" in bartokSees.name, "his progress and the time: ${bartokSees.name}")
        helper.assertEquals(BossBarColor.GREEN, bartokSees.color, "obtain is green")
        helper.assertEquals(BossBarOverlay.PROGRESS, bartokSees.overlay, "four items: no notch")
        helper.assertEquals(0f, bartokSees.progress, "nothing held yet")
        helper.assertEquals(1, mimosa.view().size, "Mimosa sees her own bar")
        helper.assertTrue(josiane.view().isEmpty(), "who does not take part sees nothing")

        val later = NOW + 61_000
        val progressed = challenge.copy(participants = listOf(challenge.participants[0].copy(progress = 2), challenge.participants[1]))
        bars.render(helper.level.server, state(progressed), ui, later)
        val updated = bartok.view().values.single()
        helper.assertTrue("2/4" in updated.name && "13:59" in updated.name, "updated: ${updated.name}")
        helper.assertEquals(0.5f, updated.progress, "half the eggs")
        helper.assertTrue("0/4" in mimosa.view().values.single().name, "Mimosa's own progress: ${mimosa.view()}")
        helper.assertEquals(1, bartok.packets().count { it.isAdd() }, "updated in place, never added twice")

        val sent = bartok.packets().size
        bars.render(helper.level.server, state(progressed), ui, later)
        helper.assertEquals(sent, bartok.packets().size, "nothing changed, nothing sent")
    }

    @GameTest
    fun aBarFollowsItsPlayerThroughARespawn(helper: GameTestHelper) = withClients(helper, 1) { (bartok) ->
        val bars = Bossbars()
        val ui = ui(helper)
        val challenge = challenge(51, MechanicsSamples.naked, bartok)
        bars.render(helper.level.server, state(challenge), ui, NOW)
        val before = bartok.view()
        helper.assertTrue("10:00" in before.values.single().name, "shown: $before")

        val old = bartok.player
        old.setGameMode(GameType.SURVIVAL)
        old.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
        old.hurtServer(helper.level, helper.level.damageSources().magic(), 1_000f)
        helper.assertTrue(old.isDeadOrDying, "Bartok died")
        bartok.player = helper.level.server.playerList.respawn(old, false, Entity.RemovalReason.KILLED)
        helper.assertTrue(bartok.player !== old, "a new player object")
        bars.onRespawn(old, bartok.player)
        helper.assertEquals(before, bartok.view(), "the client still shows the bar")

        val sent = bartok.packets().size
        bars.render(helper.level.server, state(challenge), ui, NOW + 30_000)
        val updates = bartok.packets().drop(sent)
        helper.assertTrue(updates.isNotEmpty() && updates.none { it.isAdd() || it.isRemove() }, "the new player object is followed: only updates")
        helper.assertTrue("09:30" in bartok.view().values.single().name, "and the bar goes on: ${bartok.view()}")
    }

    @GameTest
    fun aBarIsTakenDownWhenItsPlayerLeavesAndShownAgainWhenTheyComeBack(helper: GameTestHelper) = withClients(helper, 1) { (bartok) ->
        val bars = Bossbars()
        val ui = ui(helper)
        val challenge = challenge(52, MechanicsSamples.nether, bartok)
        bars.render(helper.level.server, state(challenge), ui, NOW)
        helper.assertEquals(1, bartok.view().size, "shown")

        bars.onLeave(bartok.player) // what the engine's part does at LEAVE
        bartok.leave()
        helper.assertTrue(bartok.view().isEmpty(), "taken down when he leaves")
        bars.render(helper.level.server, state(challenge), ui, NOW + 1_000)
        helper.assertTrue(bartok.view().isEmpty(), "nothing for an absent participant")

        bartok.join() // a new connection: a new client, without any bar
        bars.render(helper.level.server, state(challenge), ui, NOW + 2_000)
        val back = bartok.view().values.single()
        helper.assertTrue("19:58" in back.name, "shown again at the next drawing: ${back.name}")
        helper.assertEquals(BossBarColor.BLUE, back.color, "reach is blue")
    }

    @GameTest
    fun theBarsComeBackAfterARestartFromTheSavedState(helper: GameTestHelper) = withClients(helper, 1) { (bartok) ->
        val ui = ui(helper)
        val saved = state(challenge(53, MechanicsSamples.spiders, bartok))
        val before = Bossbars()
        before.render(helper.level.server, saved, ui, NOW)
        val shown = bartok.view().values.single()
        helper.assertEquals(BossBarOverlay.NOTCHED_10, shown.overlay, "ten spiders, ten notches")

        before.hideEverything() // the engine stops
        helper.assertTrue(bartok.view().isEmpty(), "taken down when the server stops")

        // The state is saved with the world as its codec writes it; the bars, in memory, are not.
        val reloaded = MechanicsState.decode(MechanicsState.encode(saved))
        helper.assertEquals(saved, reloaded, "the state comes back from the world")
        Bossbars().render(helper.level.server, reloaded, ui, NOW)
        helper.assertEquals(shown, bartok.view().values.single(), "the same bar, drawn again by a new engine")
    }

    @GameTest
    fun theBarsOfAnEndedChallengeAreTakenDown(helper: GameTestHelper) = withClients(helper, 2) { (bartok, mimosa) ->
        val bars = Bossbars()
        val ui = ui(helper)
        val egg = challenge(54, MechanicsSamples.turtleEgg, bartok, mimosa)
        val riddle = challenge(55, MechanicsSamples.riddle, bartok, mimosa)
        val invited = MechanicsSamples.invitedDuel.copy(id = 56, participants = participants(bartok, mimosa))
        bars.render(helper.level.server, MechanicsState(challenges = listOf(egg, riddle, invited)), ui, NOW)
        helper.assertEquals(2, bartok.view().size, "one bar per challenge; none for a duel that waits: ${bartok.view()}")

        // C-54 ended: it left the state, its bars go at the next drawing; Mimosa is no longer in C-55; the duel started.
        val withoutMimosa = riddle.copy(participants = riddle.participants.take(1))
        val duel = invited.copy(status = ChallengeStatus.RUNNING, startedAt = NOW, endsAt = NOW + 5 * MINUTE)
        bars.render(helper.level.server, MechanicsState(challenges = listOf(withoutMimosa, duel)), ui, NOW + 1_000)
        val names = bartok.view().values.map { it.name }
        helper.assertTrue(names.size == 2 && names.any { "Énigme" in it } && names.any { "Duel au soleil" in it }, "the riddle and the duel: $names")
        helper.assertTrue(names.none { "Œuf de tortue" in it }, "the egg is gone")
        helper.assertEquals(listOf(BossBarColor.RED), mimosa.view().values.map { it.color }, "Mimosa keeps only the duel, red")

        bars.hideAll(Bossbars.key(55)) // an end outside the drawing (end_challenge): taken down at once
        helper.assertTrue(bartok.view().values.none { "Énigme" in it.name }, "the riddle is gone: ${bartok.view()}")
        bars.render(helper.level.server, MechanicsState.EMPTY, ui, NOW + 2_000)
        helper.assertTrue(bartok.view().isEmpty() && mimosa.view().isEmpty(), "nothing left once nothing runs")
    }

    private companion object {
        const val MINUTE = 60_000L

        /** The tests' clock: what the engine's state would hold. */
        val NOW: Long = MechanicsSamples.evening

        fun ui(helper: GameTestHelper): UiTexts = helper.narrator().settings.get().ui

        fun state(vararg challenges: ChallengeState) = MechanicsState(challenges = challenges.toList())

        fun participants(vararg clients: BarClient) = clients.map { Participant(it.uuid.toString(), it.name) }

        /** Challenge [id] of [spec], started at [NOW], with [clients] as its participants. */
        fun challenge(id: Long, spec: ChallengeSpec, vararg clients: BarClient) = ChallengeState(
            id = id,
            spec = spec,
            status = ChallengeStatus.RUNNING,
            createdAt = NOW,
            startedAt = NOW,
            endsAt = NOW + spec.minutes * MINUTE,
            participants = participants(*clients),
            turnId = 42,
            conversationId = "director-c13",
        )

        /** Runs [test] with [count] new [BarClient]s, removed however it ends; the test succeeds when [test] returns. */
        fun withClients(helper: GameTestHelper, count: Int, test: (List<BarClient>) -> Unit) {
            val clients = List(count) { BarClient(helper) }
            try {
                test(clients)
            } finally {
                clients.forEach { it.close() }
            }
            helper.succeed()
        }

        fun ClientboundBossEventPacket.isAdd(): Boolean = operation() == "add"

        fun ClientboundBossEventPacket.isRemove(): Boolean = operation() == "remove"

        /** What the packet does: the name of the handler method it calls. */
        fun ClientboundBossEventPacket.operation(): String {
            var operation = ""
            dispatch(object : ClientboundBossEventPacket.Handler {
                override fun add(
                    id: UUID, name: Component, progress: Float, color: BossBarColor, overlay: BossBarOverlay,
                    darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean,
                ) { operation = "add" }

                override fun remove(id: UUID) { operation = "remove" }

                override fun updateProgress(id: UUID, progress: Float) { operation = "progress" }

                override fun updateName(id: UUID, name: Component) { operation = "name" }

                override fun updateStyle(id: UUID, color: BossBarColor, overlay: BossBarOverlay) { operation = "style" }

                override fun updateProperties(id: UUID, darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean) { operation = "properties" }
            })
            return operation
        }

    }

    /** A boss bar as the client shows it. */
    private data class ShownBar(val name: String, val progress: Float, val color: BossBarColor, val overlay: BossBarOverlay)

    /**
     * A player whose connection records the boss bar packets the server sends them. [join] connects them (again)
     * with a new connection, as a client that comes back: it starts without any bar, like a real client, which
     * drops every bar when it leaves a server. [view] plays the packets of the current connection back.
     */
    private class BarClient(private val helper: GameTestHelper) : AutoCloseable {
        val name = "bb_" + UUID.randomUUID().toString().replace("-", "").take(13)
        private val profile = GameProfile(UUID.randomUUID(), name)
        val uuid: UUID get() = profile.id()
        private var connection = BossPackets()

        /** The live player object: a new one after a respawn or a [join]. */
        lateinit var player: ServerPlayer

        init {
            join()
        }

        fun join() {
            val server = helper.level.server
            connection = BossPackets()
            player = ServerPlayer(server, helper.level, profile, ClientInformation.createDefault())
            EmbeddedChannel(connection)
            server.playerList.placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false))
        }

        fun leave() {
            helper.level.server.playerList.remove(player)
        }

        /** The boss bar packets of the current connection, in order. */
        fun packets(): List<ClientboundBossEventPacket> = connection.recorded.toList()

        /** The bars the client shows, by id: the packets played back in order, as `BossHealthOverlay.update` does. */
        fun view(): Map<UUID, ShownBar> {
            val bars = LinkedHashMap<UUID, ShownBar>()
            packets().forEach { packet ->
                packet.dispatch(object : ClientboundBossEventPacket.Handler {
                    override fun add(
                        id: UUID, name: Component, progress: Float, color: BossBarColor, overlay: BossBarOverlay,
                        darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean,
                    ) { bars[id] = ShownBar(name.string, progress, color, overlay) }

                    override fun remove(id: UUID) { bars.remove(id) }

                    override fun updateProgress(id: UUID, progress: Float) { bars.computeIfPresent(id) { _, bar -> bar.copy(progress = progress) } }

                    override fun updateName(id: UUID, name: Component) { bars.computeIfPresent(id) { _, bar -> bar.copy(name = name.string) } }

                    override fun updateStyle(id: UUID, color: BossBarColor, overlay: BossBarOverlay) {
                        bars.computeIfPresent(id) { _, bar -> bar.copy(color = color, overlay = overlay) }
                    }
                })
            }
            return bars
        }

        override fun close() {
            val players = helper.level.server.playerList
            players.getPlayer(uuid)?.let { players.remove(it) }
        }
    }

    /** A connection that keeps the boss bar packets, and sends everything on as usual. */
    private class BossPackets : Connection(PacketFlow.SERVERBOUND) {
        val recorded = CopyOnWriteArrayList<ClientboundBossEventPacket>()

        override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
            if (packet is ClientboundBossEventPacket) recorded += packet
            super.send(packet, listener, flush)
        }
    }
}
