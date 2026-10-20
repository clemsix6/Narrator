package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.api.Creature
import io.github.clemsix6.narrator2.forge.api.Creatures
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.World
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The SDK behind the bridge (M5 §1.1): each SDK object behind one stable proxy, values as they are, refusals thrown as they are. */
class SdkBridgeTest {
    private val handle = fake(Creature::class.java) { method, _ -> if (method.name == "uuid") "u-1" else null }
    private val creatures = fake(Creatures::class.java) { method, _ ->
        when (method.name) {
            "own" -> listOf(handle)
            "get" -> handle
            else -> throw ForgeRefusal("Creatures.claim: refused")
        }
    }
    private val world = fake(World::class.java) { _, _ -> emptyList<Any>() }
    private val context = fake(ModuleContext::class.java) { method, _ ->
        when (method.name) {
            "creatures" -> creatures
            "world" -> world
            "id" -> "C-70"
            else -> null
        }
    }

    @Test
    fun `an SDK object is behind one stable proxy, and so are the ones it hands out`() {
        val bridge = SdkBridge()
        val seen = bridge.wrap(ModuleContext::class.java, context)
        assertSame(seen, bridge.wrap(ModuleContext::class.java, context))
        assertNotSame(context, seen)
        assertEquals("C-70", seen.id())
        val own = seen.creatures().own()
        assertNotSame(handle, own.single())
        assertSame(own.single(), seen.creatures().get("u-1"), "the same handle twice: a module may compare them")
        assertSame(seen.creatures(), seen.creatures())
        assertEquals("u-1", own.single().uuid())
        assertTrue(own.single() == seen.creatures().get("u-1") && own.single().hashCode() == System.identityHashCode(own.single()))
        assertEquals("SDK Creature", own.single().toString())
        assertFailsWith<UnsupportedOperationException> { (own as MutableList<Creature>).add(handle) }
    }

    @Test
    fun `values pass as they are, refusals are thrown as they are`() {
        val seen = SdkBridge().wrap(ModuleContext::class.java, context)
        assertEquals(emptyList(), seen.world().players())
        val refused = assertFailsWith<ForgeRefusal> { seen.creatures().claim("u-2") }
        assertEquals("Creatures.claim: refused", refused.message)
    }

    @Test
    fun `only the SDK's interfaces handed to modules are wrapped`() {
        val bridge = SdkBridge()
        assertFailsWith<IllegalArgumentException> { bridge.wrap(GameFact::class.java, GameFact.Slept("Bartok", Place("overworld", 0.0, 64.0, 0.0))) }
        assertFailsWith<IllegalArgumentException> { bridge.wrap(Runnable::class.java, Runnable { }) }
    }

    private fun <T : Any> fake(type: Class<T>, answer: (java.lang.reflect.Method, Array<Any?>) -> Any?): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.get(0)
                    "toString" -> "fake ${type.simpleName}"
                    else -> answer(method, arguments ?: arrayOf())
                }
            },
        )
}
