package io.github.clemsix6.narrator2.testing

import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.params.ParameterizedTest
import java.io.File
import java.lang.reflect.Method
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards against a silent Kotlin trap: `@Test fun x() = runBlocking { ... }` takes the type of the last
 * expression of the block as its return type. When that type is not `Unit` (an `assertFailsWith`, a list...),
 * the compiled method does not return `void` and JUnit 5 skips it without a word, so the test never runs.
 * The fix is `runBlocking<Unit> { ... }` or a block body.
 *
 * This test scans every compiled class in the classpath directories and fails with the list of offenders.
 */
class TestMethodsReturnUnitTest {
    private val testAnnotations = listOf(Test::class.java, ParameterizedTest::class.java, RepeatedTest::class.java)

    /** Every test method whose return type is not `void`. `@TestFactory` methods return values by design and are not checked. */
    private fun offenders(classes: List<Class<*>>): List<String> = classes.flatMap { type ->
        type.declaredMethods
            .filter { method -> testAnnotations.any { method.isAnnotationPresent(it) } && method.returnType != Void.TYPE }
            .map { describe(it) }
    }.sorted()

    private fun describe(method: Method) = "${method.declaringClass.name}.${method.name} returns ${method.returnType.name}"

    private fun classesOnClasspath(): List<Class<*>> {
        val roots = System.getProperty("java.class.path").split(File.pathSeparator)
            .map(::File)
            .filter { it.isDirectory }
        val loader = javaClass.classLoader
        return roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "class" && !it.name.startsWith("module-info") }
                .map { root.toPath().relativize(it.toPath()).toString().removeSuffix(".class").replace(File.separatorChar, '.') }
                .filter { it != Faulty::class.java.name }
                .map { Class.forName(it, false, loader) }
                .toList()
        }
    }

    @Test
    fun `every test method returns void`() {
        val classes = classesOnClasspath()
        assertTrue(classes.any { it == javaClass }, "the scan does not see its own class: the classpath layout changed")
        val found = offenders(classes)
        if (found.isNotEmpty()) {
            fail("These test methods do not return void, so JUnit silently skips them (use runBlocking<Unit> or a block body):\n" + found.joinToString("\n"))
        }
    }

    @Test
    fun `a test method that returns a value is detected, a test factory is not`() {
        val found = offenders(listOf(Faulty::class.java))
        assertTrue(found.size == 1 && found.single().endsWith("Faulty.silentlySkipped returns java.lang.String"), "unexpected offenders: $found")
    }

    /** Only reflected on, never run (JUnit does not pick up private nested classes), and left out of the scan. */
    @Suppress("unused")
    private class Faulty {
        @Test
        fun silentlySkipped() = "not unit"

        @Test
        fun fine() {
        }

        @TestFactory
        fun factory() = listOf<Any>()
    }
}
