package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.test.Bench
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.testing.TestServices
import kotlinx.coroutines.runBlocking
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `sdk.md` tells the forger the truth of the SDK (M5 §8.2, task M5-13): a reference that lies costs it tries.
 *
 * - Its reference blocks (the ```java blocks that declare types) are read line by line: each type, method,
 *   constructor, constant, enum constant, record component and annotation default is checked against the SDK by
 *   reflection, and every public one of the SDK's two packages must be there.
 * - Its prose names only what exists: `Spawn.of`, `ctx.world().player`, `b.refuseNext`, a type or a constant.
 * - Its examples (the blocks with a whole module) pass `forge_test` through the real pipeline, bench and scenarios
 *   included; its snippets (the other blocks) compile and pass the guard inside a module of their own.
 */
class SdkDocTest {
    private val doc = Prompts.bundled().get(Prompts.SDK)
    private val blocks = BLOCK.findAll(doc).map { Block(it.groupValues[1], doc.substring(0, it.range.first).count { c -> c == '\n' } + 2) }.toList()
    private val references = blocks.filter { block -> block.text.lines().any { TYPE_START.containsMatchIn(it) } && !block.isModule }
    private val modules = blocks.filter { it.isModule }
    private val snippets = blocks - references.toSet() - modules.toSet()

    private class Block(val text: String, val firstLine: Int) {
        val isModule = "implements ForgeModule" in text && "@ModuleInfo" in text
    }

    // ── The reference ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every declaration of the reference is the SDK's, as the SDK has it`() {
        assertTrue(references.isNotEmpty(), "sdk.md has no reference block")
        val problems = mutableListOf<String>()
        for (declaration in declarations()) declaration.check(problems)
        assertEquals(emptyList(), problems, "sdk.md says what the SDK does not have")
    }

    @Test
    fun `the reference names every public type, member, constant and enum constant of the SDK`() {
        val declared = declarations()
        val types = declared.filterIsInstance<TypeDecl>().map { it.key }.toSet()
        val missing = mutableListOf<String>()
        for ((key, type) in SDK) {
            if (key !in types) {
                missing += "the type $key"
                continue
            }
            val self = declared.filterIsInstance<TypeDecl>().first { it.key == key }
            val members = declared.filter { it.owner == key }
            val methods = members.filterIsInstance<MethodDecl>().map { it.name }.toSet() + self.components.orEmpty().map { it.second }
            for (method in type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge && !generated(type, it) }) {
                if (method.name !in methods) missing += "$key.${method.name}"
            }
            val fields = members.filterIsInstance<FieldDecl>().map { it.name }.toSet() + self.constants
            for (field in type.declaredFields.filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && !it.isSynthetic }) {
                if (field.name !in fields) missing += "$key.${field.name}"
            }
            if (!type.isRecord && type.constructors.isNotEmpty() && members.none { it is CtorDecl }) missing += "the constructor of $key"
        }
        assertEquals(emptyList(), missing, "public parts of the SDK that sdk.md never declares")
    }

    // ── The prose ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the prose names only types, members and constants that exist`() {
        val prose = BLOCK.replace(doc, "")
        val problems = mutableListOf<String>()
        val spans = SPAN.findAll(prose).map { it.groupValues[1] }.toSet()
        assertTrue(spans.size > 100, "spans read: ${spans.size}")
        for (span in spans) {
            if (WORD.matches(span)) wordProblem(span)?.let { problems += it }
            for (match in QUALIFIED.findAll(span)) {
                val (owner, member) = match.destructured
                val type = BY_SIMPLE_NAME[owner] ?: continue
                if (member != "class" && !hasMember(type, member)) problems += "`$span`: $owner has no $member"
            }
            CHAIN.find(span)?.let { chainProblem(span, it.value)?.let { problems += it } }
        }
        assertEquals(emptyList(), problems, "sdk.md's prose names what the SDK does not have")
    }

    /**
     * A capitalized word: a type of the SDK or of the JDK, or one of [OTHER_WORDS] (the sandbox's error, the bench's players, a
     * module's own classes); in capitals, a constant of the SDK.
     */
    private fun wordProblem(word: String): String? = when {
        word == word.uppercase() -> if (SDK.values.any { type -> type.fields.any { it.name == word } }) null else "`$word` is no constant of the SDK"
        word in BY_SIMPLE_NAME || word in OTHER_WORDS || word in modules.map { mainClass(it) } -> null
        JDK_PACKAGES.any { runCatching { Class.forName("$it.$word") }.isSuccess } -> null
        else -> "`$word` is no type of the SDK nor of the JDK"
    }

    /** `ctx.world().player`, `b.refuseNext`, `ground().place`: each step a method of what the step before returns. */
    private fun chainProblem(span: String, chain: String): String? {
        val steps = chain.split('.').map { it.removeSuffix("()") }
        var type: Class<*> = when (steps.first()) {
            "ctx" -> ModuleContext::class.java
            "b" -> Bench::class.java
            else -> ModuleContext::class.java
        }
        val members = if (steps.first() == "ctx" || steps.first() == "b") steps.drop(1) else steps
        for (member in members) {
            val method = type.methods.firstOrNull { it.name == member } ?: return "`$span`: ${type.simpleName} has no $member"
            type = method.returnType
        }
        return null
    }

    private fun hasMember(type: Class<*>, member: String): Boolean =
        type.methods.any { it.name == member } || type.fields.any { it.name == member } || type.classes.any { it.simpleName == member }

    /** The class of an example module: `word_race` → `WordRace`. */
    private fun mainClass(module: Block): String =
        MODULE_NAME.find(module.text)?.groupValues?.get(1).orEmpty().split('_').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }

    // ── The code ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `each example module passes forge_test through the real pipeline, its scenarios included`() = runBlocking<Unit> {
        assertTrue(modules.isNotEmpty(), "sdk.md has no example module")
        withForge { forge ->
            for (module in modules) {
                val name = requireNotNull(MODULE_NAME.find(module.text)) { "no @ModuleInfo name in the example at line ${module.firstLine}" }.groupValues[1]
                val report = forge.test(name, module.text)
                assertTrue(report.ok, "the example $name (sdk.md line ${module.firstLine}) fails forge_test:\n${report.text}")
                val scenarios = report.manifest!!.scenarios
                assertTrue(scenarios.size >= 2, "the example $name has ${scenarios.size} scenarios: two at least, as sdk.md asks")
                assertEquals(scenarios, report.scenariosPassed, "the example $name: ${report.text}")
            }
        }
    }

    @Test
    fun `each snippet compiles and passes the guard, in a module of its own`() = runBlocking<Unit> {
        assertTrue(snippets.isNotEmpty(), "sdk.md has no snippet")
        withForge { forge ->
            for (snippet in snippets) {
                val report = forge.test(SNIPPET_NAME, SNIPPET_MODULE.replace("SNIPPET", snippet.text.prependIndent("        ")))
                assertTrue(report.ok, "the snippet at sdk.md line ${snippet.firstLine} fails forge_test:\n${report.text}")
            }
        }
    }

    private suspend fun withForge(body: suspend (ForgeService) -> Unit) {
        val dir = Files.createTempDirectory("narrator2-sdk-doc")
        val services = TestServices(dataDir = dir)
        val forge = ForgeService(services, ArsenalStore(dir.resolve(ForgeService.ARSENAL_DIR), services.clock, builtInNames = emptyList()))
        try {
            body(forge)
        } finally {
            forge.stop()
        }
    }

    // ── Reading the reference ─────────────────────────────────────────────────────────────────────────────

    /** One declaration of a reference block, at [line] of `sdk.md`, in the type [owner] (`GameFact.Chat`; null at the top). */
    private sealed class Decl(val owner: String?, val line: Int) {
        abstract fun check(problems: MutableList<String>)

        protected fun type(problems: MutableList<String>): Class<*>? =
            SDK[owner].also { if (it == null) problems += "line $line: no type $owner in the SDK" }
    }

    private class TypeDecl(
        val key: String, val kind: String, val modifiers: Set<String>, val components: List<Pair<String, String>>?, val extends: String?,
        val constants: List<String>, line: Int,
    ) : Decl(key.substringBeforeLast('.', "").ifEmpty { null }, line) {
        override fun check(problems: MutableList<String>) {
            val type = SDK[key] ?: return run { problems += "line $line: no type $key in the SDK" }
            val actual = when {
                type.isAnnotation -> "@interface"
                type.isEnum -> "enum"
                type.isRecord -> "record"
                type.isInterface -> "interface"
                else -> "class"
            }
            if (actual != kind) problems += "line $line: $key is $actual, not $kind"
            if ("sealed" in modifiers && !type.isSealed) problems += "line $line: $key is not sealed"
            if ("final" in modifiers && !Modifier.isFinal(type.modifiers)) problems += "line $line: $key is not final"
            if (extends != null && type.superclass?.simpleName != extends) problems += "line $line: $key extends ${type.superclass?.simpleName}, not $extends"
            if (components != null) {
                val real = type.recordComponents.orEmpty().map { it.type.simpleName to it.name }
                if (real != components) problems += "line $line: $key is ${real.joinToString { "${it.first} ${it.second}" }}"
            }
            if (type.isEnum && type.enumConstants.map { (it as Enum<*>).name } != constants) {
                problems += "line $line: $key's constants are ${type.enumConstants.joinToString()}"
            }
        }
    }

    private class MethodDecl(
        owner: String, val name: String, val params: List<String>, val returns: String, val modifiers: Set<String>, val default: String?, line: Int,
    ) : Decl(owner, line) {
        override fun check(problems: MutableList<String>) {
            val type = type(problems) ?: return
            val method = type.methods.firstOrNull { it.name == name && it.parameterTypes.map { p -> p.simpleName } == params }
            if (method == null) {
                val real = type.methods.filter { it.name == name }.map { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }
                problems += "line $line: $owner has no $name(${params.joinToString()})" + if (real.isEmpty()) "" else "; it has ${real.joinToString()}"
                return
            }
            if (method.returnType.simpleName != returns) problems += "line $line: $owner.$name returns ${method.returnType.simpleName}, not $returns"
            if (("static" in modifiers) != Modifier.isStatic(method.modifiers)) problems += "line $line: $owner.$name static is wrong"
            if (("default" in modifiers) != method.isDefault) problems += "line $line: $owner.$name default is wrong"
            if (default != null && !sameDefault(default, method.defaultValue)) problems += "line $line: $owner.$name's default is ${method.defaultValue}, not $default"
        }

        private fun sameDefault(written: String, real: Any?): Boolean = when (real) {
            is Array<*> -> written == "{}" && real.isEmpty()
            is String -> written == "\"$real\""
            is Double -> written.removePrefix("Double.").let { it == "NEGATIVE_INFINITY" && real == Double.NEGATIVE_INFINITY || it == "POSITIVE_INFINITY" && real == Double.POSITIVE_INFINITY || it.toDoubleOrNull() == real }
            else -> written == real.toString()
        }
    }

    private class CtorDecl(owner: String, val params: List<String>, line: Int) : Decl(owner, line) {
        override fun check(problems: MutableList<String>) {
            val type = type(problems) ?: return
            if (type.constructors.none { it.parameterTypes.map { p -> p.simpleName } == params }) problems += "line $line: $owner has no constructor (${params.joinToString()})"
        }
    }

    private class FieldDecl(owner: String, val name: String, val value: String, line: Int) : Decl(owner, line) {
        override fun check(problems: MutableList<String>) {
            val type = type(problems) ?: return
            val field = type.fields.firstOrNull { it.name == name } ?: return run { problems += "line $line: $owner has no $name" }
            val real = field.get(null).toString()
            if (real != value.replace("_", "").replace(",", "")) problems += "line $line: $owner.$name is $real, not $value"
        }
    }

    /** Every declaration of the reference blocks; a line that is none fails the test. */
    private fun declarations(): List<Decl> {
        val all = mutableListOf<Decl>()
        val unread = mutableListOf<String>()
        for (block in references) {
            val open = ArrayDeque<String>()
            block.text.lines().forEachIndexed { index, raw ->
                val line = block.firstLine + index
                val text = raw.substringBefore("//").trim()
                when {
                    text.isEmpty() -> Unit
                    text == "}" -> open.removeLastOrNull() ?: run { unread += "line $line: a } that closes nothing" }
                    else -> {
                        val header = HEADER.matchEntire(text)
                        if (header != null) {
                            val (modifiers, kind, name, components, extends, rest) = header.destructured
                            val key = (open.lastOrNull()?.let { "$it." }.orEmpty()) + name
                            val inline = rest.trim().takeIf { it.endsWith("}") }?.removeSuffix("}")
                            val constants = if (kind == "enum") inline.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
                            val parts = if (header.groups[4] != null) params(components).map { erase(it.substringBeforeLast(' ').trim()) to it.substringAfterLast(' ') } else null
                            all += TypeDecl(key, kind, modifiers.split(' ').filter { it.isNotBlank() }.toSet(), parts, extends.ifEmpty { null }, constants, line)
                            if (inline == null) {
                                open.addLast(key)
                            } else if (kind != "enum") {
                                members(key, inline, line, all, unread)
                            }
                        } else {
                            val owner = open.lastOrNull() ?: return@forEachIndexed run { unread += "line $line: outside a type: $text" }
                            members(owner, text, line, all, unread)
                        }
                    }
                }
            }
            if (open.isNotEmpty()) unread += "the block at line ${block.firstLine} leaves ${open.joinToString()} open"
        }
        assertEquals(emptyList(), unread, "lines of sdk.md's reference that this test cannot read")
        return all
    }

    /** The members of [text] (one or several, each ended by `;`), declared in [owner]. */
    private fun members(owner: String, text: String, line: Int, into: MutableList<Decl>, unread: MutableList<String>) {
        for (member in split(text, ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val simple = owner.substringAfterLast('.')
            val ctor = CTOR.matchEntire(member)?.takeIf { it.groupValues[1] == simple }
            val field = FIELD.matchEntire(member)
            val method = METHOD.matchEntire(member)
            when {
                ctor != null -> into += CtorDecl(owner, params(ctor.groupValues[2]).map { erase(it.substringBeforeLast(' ')) }, line)
                field != null -> split(field.groupValues[2], ',').forEach { declarator ->
                    into += FieldDecl(owner, declarator.substringBefore('=').trim(), declarator.substringAfter('=').trim(), line)
                }
                method != null -> {
                    val (modifiers, returns, name, params, default) = method.destructured
                    into += MethodDecl(
                        owner, name, params(params).map { erase(it.substringBeforeLast(' ')) }, erase(returns),
                        modifiers.split(' ').filter { it.isNotBlank() }.toSet(), default.ifEmpty { null }, line,
                    )
                }
                else -> unread += "line $line: $member"
            }
        }
    }

    private companion object {
        val BLOCK = Regex("```java\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val TYPE_START = Regex("^(?:sealed |final )*(?:interface|class|record|enum|@interface) \\w")
        val HEADER = Regex(
            "((?:(?:sealed|final|abstract|static|public)\\s+)*)(interface|class|record|enum|@interface)\\s+(\\w+)\\s*" +
                "(?:\\(([^)]*)\\))?\\s*(?:extends\\s+([\\w.]+)\\s*)?\\{(.*)",
        )
        val CTOR = Regex("(\\w+)\\s*\\((.*)\\)")
        val FIELD = Regex("((?:(?:static|final|public)\\s+)*)[\\w<>\\[\\]]+\\s+([A-Z][A-Z0-9_]*\\s*=.+)")
        val METHOD = Regex("((?:(?:static|default|public|abstract)\\s+)*)(?:<[^>]+>\\s+)?([\\w.<>\\[\\]?, ]+?)\\s+(\\w+)\\s*\\((.*)\\)(?:\\s+default\\s+(.+))?")
        val MODULE_NAME = Regex("@ModuleInfo\\(name = \"([a-z0-9_]+)\"")
        val SPAN = Regex("`([^`\\n]+)`")
        val WORD = Regex("[A-Z][A-Za-z0-9_]*")
        val QUALIFIED = Regex("\\b([A-Z]\\w*)\\.([a-zA-Z_]\\w*)")
        val CHAIN = Regex("^(?:ctx|b|[a-z]\\w*\\(\\))(?:\\.[a-zA-Z]\\w*(?:\\(\\))?)+")
        val JDK_PACKAGES = listOf("java.lang", "java.util", "java.util.function", "java.util.stream", "java.util.concurrent", "java.util.concurrent.atomic", "java.time")
        val OTHER_WORDS = setOf("ForgeLimit", "Scenarios", "Bartok", "Mimosa", "Josiane")

        const val SNIPPET_NAME = "sdk_snippet"
        val SNIPPET_MODULE = """
            import io.github.clemsix6.narrator2.forge.api.*;
            import io.github.clemsix6.narrator2.forge.api.test.Bench;
            import java.util.*;

            @ModuleInfo(name = "sdk_snippet", summary = "A snippet of sdk.md, compiled and never run.")
            public final class SdkSnippet implements ForgeModule {
                @Override
                public void start(ModuleContext ctx) {}

                private void snippet(ModuleContext ctx, Bench b) {
            SNIPPET
                }
            }
        """.trimIndent() + "\n"

        /** Every public type of the SDK's two packages, by its key: `Acts`, `GameFact.Chat`, `Bench`. */
        val SDK: Map<String, Class<*>> = listOf("io/github/clemsix6/narrator2/forge/api", "io/github/clemsix6/narrator2/forge/api/test").flatMap { dir ->
            val folder = Path.of(requireNotNull(ForgeModule::class.java.classLoader.getResource(dir)) { dir }.toURI())
            Files.list(folder).use { files -> files.map { it.fileName.toString() }.filter { it.endsWith(".class") }.toList() }
                .map { Class.forName(dir.replace('/', '.') + "." + it.removeSuffix(".class")) }
        }.filter { Modifier.isPublic(it.modifiers) }.associateBy { it.name.substringAfterLast('.').replace('$', '.') }

        val BY_SIMPLE_NAME: Map<String, Class<*>> = SDK.values.associateBy { it.simpleName }

        /** The methods the compiler writes for a record or an enum, which `sdk.md` need not name. */
        fun generated(type: Class<*>, method: Method): Boolean =
            type.isRecord && method.name in setOf("equals", "hashCode", "toString") || type.isEnum && method.name in setOf("values", "valueOf")

        /** [text] split on [separator] outside `<>`, `()` and `{}`. */
        fun split(text: String, separator: Char): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            text.forEachIndexed { i, c ->
                when (c) {
                    '<', '(', '{' -> depth++
                    '>', ')', '}' -> depth--
                    separator -> if (depth == 0) {
                        parts += text.substring(start, i)
                        start = i + 1
                    }
                }
            }
            parts += text.substring(start)
            return parts
        }

        /** The parameters of a declaration: `Class<F> type, Consumer<F> handler` → each `type name`. */
        fun params(text: String): List<String> = split(text, ',').map { it.trim() }.filter { it.isNotEmpty() }

        /** A written type as reflection names it: generics erased, `String...` an array, a nested type by its own name. */
        fun erase(written: String): String {
            var type = written.trim()
            while ('<' in type) type = type.replace(GENERICS, "")
            return type.replace("...", "[]").substringAfterLast('.').trim()
        }

        val GENERICS = Regex("<[^<>]*>")

        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
