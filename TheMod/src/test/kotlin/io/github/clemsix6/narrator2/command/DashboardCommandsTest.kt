package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.web.Dashboard
import io.github.clemsix6.narrator2.web.DisabledDashboard
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DashboardCommandsTest {
    private val token = "a".repeat(64)

    private val dashboard = object : Dashboard {
        override val url = "http://127.0.0.1:8770/"
        override val unavailableReason: String? = null
        override fun loginUrl() = "${url}login?key=$token"
        override fun resetToken() = Unit
        override fun stop() = Unit
    }

    @Test
    fun `a player gets the login link as a click, the token out of the text`() {
        val lines = DashboardCommands.lines(dashboard, player = true)
        val text = lines.joinToString("\n") { it.string }
        assertFalse(token in text, "the chat's text shows the token: $text")
        assertContains(text, "dashboard on http://127.0.0.1:8770/")
        assertContains(text, "[Sign in]")
        assertContains(text, "ssh -L 8770:localhost:8770")
        assertEquals(listOf(ClickEvent.OpenUrl(URI.create("http://127.0.0.1:8770/login?key=$token"))), lines.flatMap(::clicks))
    }

    @Test
    fun `a console gets the link written out, and the advice to reset it`() {
        val text = DashboardCommands.lines(dashboard, player = false).joinToString("\n") { it.string }
        assertContains(text, "Sign in: http://127.0.0.1:8770/login?key=$token")
        assertContains(text, "logs/latest.log")
        assertContains(text, "/narrator dashboard reset")
    }

    @Test
    fun `without a dashboard, it says why`() {
        val text = DashboardCommands.lines(DisabledDashboard("dashboard.enabled is false"), player = true).joinToString("\n") { it.string }
        assertContains(text, "no dashboard: dashboard.enabled is false.")
        assertContains(text, "read when the server starts")
    }

    private fun clicks(component: Component): List<ClickEvent> = listOfNotNull(component.style.clickEvent) + component.siblings.flatMap(::clicks)
}
