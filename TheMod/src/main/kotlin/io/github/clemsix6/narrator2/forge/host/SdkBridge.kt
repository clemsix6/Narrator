package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.api.test.Bench
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The SDK as a module sees it (M5 §1.1, §4.1): every SDK object handed to one module — its `ModuleContext`, what that
 * hands out in turn (`World`, `Acts`, `Pending`, `Creature`, `Scheduled`…), or a scenario's `Bench` — reaches the
 * module behind a proxy that runs **each of its methods on the thread that waits for the module's call**
 * ([ModuleThreads.toCaller]): the server thread in the game, the bench's worker at the bench. The SDK's own code thus
 * never runs on a module thread, and an abandoned module thread is refused at every entry point: it can never touch
 * the world, an act, a timer, a creature, a block or the state again.
 *
 * A proxy is the same object for the same SDK object (one map per module, by identity: a module may compare the
 * handles of its creatures); its `equals`, `hashCode` and `toString` are its identity's. Values (records, texts,
 * numbers, lists of records) pass as they are; so do the objects the module hands to the SDK (lambdas, lists), which
 * the SDK copies inside the call and only ever calls inside a call of the module's own (M5 §4.1).
 */
class SdkBridge {
    /** SDK object → its proxy; touched on the caller's thread only (inside [ModuleThreads.toCaller], or before the module runs). */
    private val proxies = IdentityHashMap<Any, Any>()

    /** [target] as the module sees it: behind a proxy of [type], an interface of the SDK. */
    fun <T : Any> wrap(type: Class<T>, target: T): T {
        require(isSdkInterface(type)) { "${type.name} is not an interface of the SDK that a module is handed" }
        return type.cast(proxy(type, target))
    }

    /** Lets every proxy go (the deployment ended); a proxy the module kept still reaches its SDK object, which refuses it. */
    fun clear() {
        proxies.clear()
    }

    private fun proxy(type: Class<*>, target: Any): Any =
        proxies.getOrPut(target) { Proxy.newProxyInstance(type.classLoader, arrayOf(type), Handler(type, target)) }

    private inner class Handler(private val type: Class<*>, private val target: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, arguments: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === arguments?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "SDK ${type.simpleName}"
                }
            }
            return ModuleThreads.toCaller {
                val result = try {
                    method.invoke(target, *(arguments ?: NONE))
                } catch (wrapped: InvocationTargetException) {
                    throw wrapped.cause ?: wrapped
                }
                seen(method, result)
            }
        }
    }

    /** What the module gets back from [method]: an SDK object behind its proxy, a list of them each behind its own, a value as it is. */
    private fun seen(method: Method, result: Any?): Any? {
        if (result == null) return null
        if (isSdkInterface(method.returnType)) return proxy(method.returnType, result)
        val element = listedType(method) ?: return result
        return Collections.unmodifiableList((result as List<*>).map { item -> item?.let { proxy(element, it) } })
    }

    /** The SDK interface that a method's `List<…>` holds; null when it holds values. */
    private fun listedType(method: Method): Class<*>? {
        val type = method.genericReturnType as? ParameterizedType ?: return null
        if (type.rawType != List::class.java) return null
        return (type.actualTypeArguments.singleOrNull() as? Class<*>)?.takeIf(::isSdkInterface)
    }

    private companion object {
        val NONE = arrayOf<Any?>()
        val SDK_PACKAGES = setOf(ForgeModule::class.java.packageName, Bench::class.java.packageName)

        /** An interface the SDK implements and hands to modules: not `ForgeModule` (the module's), not a sealed family of records (`GameFact`). */
        fun isSdkInterface(type: Class<*>): Boolean =
            type.isInterface && !type.isAnnotation && !type.isSealed && type.packageName in SDK_PACKAGES && type != ForgeModule::class.java
    }
}
