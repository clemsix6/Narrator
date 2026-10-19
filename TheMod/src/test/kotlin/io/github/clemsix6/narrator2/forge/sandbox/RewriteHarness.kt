package io.github.clemsix6.narrator2.forge.sandbox

import io.github.clemsix6.narrator2.forge.Built
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.rt.Meter
import io.github.clemsix6.narrator2.testing.ForgeSamples
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Random
import java.util.function.Consumer

/**
 * Modules for the tests of the rewriting (M5 §3.4): compiled by ECJ against the SDK ([ForgeSamples.compile]),
 * rewritten by [Instrumenter], loaded by a loader of their own and called under their meter, on a thread of their
 * own (a shallow stack, and its own count of allocated bytes). What the loader and the host (task M5-05) do, reduced
 * to what these tests need: the loader here sees all of `java.*`.
 */
internal object Rewrites {
    /** A module `[name]` with [members] and [body] as its `start(ModuleContext m)`. */
    fun source(name: String, members: String, body: String): String = """
        import io.github.clemsix6.narrator2.forge.api.*;
        import java.util.*;
        import java.util.function.*;
        import java.util.stream.*;
        public final class $name implements ForgeModule {
        $members
            public void start(ModuleContext m) {
        $body
            }
        }
    """.trimIndent()

    fun compile(name: String, source: String): Map<String, ByteArray> {
        val compiled = ForgeSamples.compile(name, source)
        check(compiled.ok) { "$name does not compile: ${compiled.problems}" }
        return compiled.classes
    }

    /** [source] compiled and rewritten, loaded under its meter. */
    fun rewritten(name: String, source: String): LoadedModule = LoadedModule(name, Instrumenter.rewrite(name, compile(name, source)), metered = true)

    fun rewritten(name: String, members: String = "", body: String): LoadedModule = rewritten(name, source(name, members, body))

    /** [source] compiled, not rewritten: the same module as the JDK runs it, for comparison. */
    fun plain(name: String, source: String): LoadedModule = LoadedModule(name, compile(name, source), metered = false)

    fun plain(name: String, members: String = "", body: String): LoadedModule = plain(name, source(name, members, body))
}

/** Defines a module's classes; `java.*` from the platform, the SDK and `forge.rt` from the mod; nothing else. */
internal class RewriteLoader(private val classes: Map<String, ByteArray>) : ClassLoader("forge-test", null) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        val loaded = findLoadedClass(name) ?: classes[name]?.let { defineClass(name, it, 0, it.size) } ?: when {
            name.startsWith("java.") -> getPlatformClassLoader().loadClass(name)
            name.startsWith(SDK) || name.startsWith(RT) -> RewriteLoader::class.java.classLoader.loadClass(name)
            else -> throw ClassNotFoundException("$name is not visible to modules")
        }
        if (resolve) resolveClass(loaded)
        loaded
    }

    private companion object {
        const val SDK = "io.github.clemsix6.narrator2.forge.api."
        const val RT = "io.github.clemsix6.narrator2.forge.rt."
    }
}

/** How one call of a module went: what it threw, what its meter says, how long it took, what it allocated and logged. */
internal class MeteredCall(val error: Throwable?, val tripped: String?, val nanos: Long, val allocated: Long, val logs: List<String>) {
    val millis: Double get() = nanos / 1e6

    override fun toString() = "error=$error, tripped=$tripped, ${"%.1f".format(millis)} ms, ${allocated / 1024} KiB, logs=$logs"
}

/** A module loaded by a [RewriteLoader]; its [meter] set when it is [metered] (rewritten). */
internal class LoadedModule(val main: String, val classes: Map<String, ByteArray>, private val metered: Boolean) {
    val loader = RewriteLoader(classes)
    val meter = Meter(loader)
    val context = FakeContext()

    init {
        if (metered) Class.forName(main + Built.METER_SUFFIX, true, loader).getField(Built.METER_FIELD).set(null, meter)
    }

    val type: Class<*> = Class.forName(main, false, loader)
    private var instance: ForgeModule? = null

    /** Runs [body] as the host calls a module: on a thread of its own, armed with [budgetNanos] and [allocation] when metered. */
    fun call(budgetNanos: Long = ForgeLimits.CALL_NANOS, allocation: Long = ForgeLimits.CALL_ALLOCATION_BYTES, body: () -> Unit): MeteredCall {
        var result: MeteredCall? = null
        val thread = Thread {
            val logs = context.logs.size
            var error: Throwable? = null
            if (metered) meter.arm(budgetNanos, allocation)
            val start = System.nanoTime()
            try {
                body()
            } catch (thrown: Throwable) {
                error = thrown
            }
            val nanos = System.nanoTime() - start
            if (metered) meter.disarm()
            result = MeteredCall(error, if (metered) meter.tripped() else null, nanos, if (metered) meter.allocatedSinceArm() else -1, context.logs.drop(logs))
        }
        thread.start()
        thread.join()
        return result!!
    }

    /** Makes the module (its constructor and static initializer under the meter), then calls its `start`. */
    fun start(budgetNanos: Long = ForgeLimits.CALL_NANOS, allocation: Long = ForgeLimits.CALL_ALLOCATION_BYTES): MeteredCall {
        val made = call(budgetNanos, allocation) { instance = type.getConstructor().newInstance() as ForgeModule }
        if (made.error != null) return made
        return call(budgetNanos, allocation) { instance!!.start(context.context) }
    }

    /** Calls the module's static or instance method [name] with [args] (reflection, so the error is its cause). */
    fun invoke(name: String, vararg args: Any?, budgetNanos: Long = ForgeLimits.CALL_NANOS): Pair<MeteredCall, Any?> {
        var value: Any? = null
        val call = call(budgetNanos) {
            val method = type.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            method.isAccessible = true
            value = try {
                method.invoke(if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else instance, *args)
            } catch (wrapped: java.lang.reflect.InvocationTargetException) {
                throw wrapped.cause!!
            }
        }
        return call to value
    }
}

/** A module's context that does nothing but remember what the module logs and hands it: [logs], [timers], [handlers]. */
internal class FakeContext {
    val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    val timers = mutableListOf<Runnable>()
    val handlers = mutableListOf<Consumer<Any?>>()
    val storage = LinkedHashMap<String, String>()

    @Suppress("UNCHECKED_CAST")
    val context: ModuleContext = fake(ModuleContext::class.java) { method, args ->
        when (method.name) {
            "log", "tellDirector", "end" -> logs.add("${method.name}: ${args[0]}".removePrefix("log: "))
            "random" -> Random(7)
            "id" -> "C-70"
            "args" -> fake(io.github.clemsix6.narrator2.forge.api.Args::class.java) { argument, _ ->
                when (argument.name) {
                    "players" -> listOf("Bartok", "Mimosa")
                    "integer" -> 2
                    "text" -> "taquin"
                    else -> default(argument.returnType)
                }
            }
            "storage" -> fake(io.github.clemsix6.narrator2.forge.api.Storage::class.java) { access, values ->
                when (access.name) {
                    "get" -> storage[values[0] as String]
                    "put" -> storage.put(values[0] as String, values[1] as String)
                    else -> default(access.returnType)
                }
            }
            "events" -> fake(io.github.clemsix6.narrator2.forge.api.Events::class.java) { on, values ->
                handlers += values[1] as Consumer<Any?>
                default(on.returnType)
            }
            "timers" -> fake(io.github.clemsix6.narrator2.forge.api.Timers::class.java) { timer, values ->
                timers += values[1] as Runnable
                default(timer.returnType)
            }
            "game" -> null
            else -> default(method.returnType)
        }
    }

    private fun default(type: Class<*>): Any? = when {
        type == Void.TYPE -> null
        type == java.lang.Boolean.TYPE -> false
        type == Integer.TYPE -> 0
        type == java.lang.Long.TYPE -> 0L
        type == java.lang.Double.TYPE -> 0.0
        type == String::class.java -> ""
        type == List::class.java -> emptyList<Any>()
        type == Set::class.java -> emptySet<Any>()
        type.isInterface -> fake(type) { method, _ -> default(method.returnType) }
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> fake(type: Class<T>, answer: (Method, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(FakeContext::class.java.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "fake ${type.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> answer(method, args ?: emptyArray())
            }
        } as T
}
