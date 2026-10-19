package io.github.clemsix6.narrator2.forge.sandbox

import io.github.clemsix6.narrator2.forge.Built
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.rt.Guarded
import io.github.clemsix6.narrator2.forge.rt.Meter
import io.github.clemsix6.narrator2.testing.ForgeSamples
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.management.ManagementFactory
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rewriting (M5 §3.4, task M5-04): the JVM's verifier accepts what it makes without a frame recomputed, each
 * insertion is where it should be, a rewritten module behaves as the plain one does, every member it wraps exists in
 * the JDK and in `Guarded`, it refuses what it cannot bound, and what it costs.
 */
class InstrumenterTest {
    private val guarded = Guarded::class.java.methods
        .filter { Modifier.isStatic(it.modifiers) }
        .map { it.name + Type.getMethodDescriptor(it) }
        .toSet()

    /** What a class calls: `owner.name descriptor` for each method instruction, and the bootstraps and handles of its dynamic calls. */
    private class Calls(bytes: ByteArray) {
        val methods = mutableMapOf<String, MutableList<String>>()
        val frames = mutableMapOf<String, Int>()
        val handles = mutableListOf<Handle>()
        val bootstraps = mutableListOf<Handle>()
        val fields = mutableListOf<String>()

        init {
            ClassReader(bytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                        fields += "$access $name $descriptor"
                        return null
                    }

                    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                        val calls = methods.getOrPut(name + descriptor) { mutableListOf() }
                        frames[name + descriptor] = 0
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitMethodInsn(opcode: Int, owner: String, method: String, methodDescriptor: String, isInterface: Boolean) {
                                calls += "$owner.$method$methodDescriptor"
                            }

                            override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) {
                                frames.merge(name + descriptor, 1, Int::plus)
                            }

                            override fun visitInvokeDynamicInsn(call: String, callDescriptor: String, bootstrap: Handle, vararg arguments: Any?) {
                                bootstraps += bootstrap
                                arguments.filterIsInstance<Handle>().forEach { handles += it }
                            }
                        }
                    }
                },
                0,
            )
        }

        fun meterCalls(method: String, call: String) = methods.getValue(method).count { it == "io/github/clemsix6/narrator2/forge/rt/Meter.$call()V" }
    }

    // ── The verifier ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the witness module rewritten passes the verifier and behaves as it did`() {
        val rewritten = LoadedModule(ForgeSamples.CLASS, Instrumenter.rewrite(ForgeSamples.CLASS, ForgeSamples.classes), metered = true)
        val plain = LoadedModule(ForgeSamples.CLASS, ForgeSamples.classes, metered = false)
        assertEquals(ForgeSamples.classes.keys + (ForgeSamples.CLASS + Built.METER_SUFFIX), rewritten.classes.keys)

        val initialized = rewritten.call {
            rewritten.classes.keys.forEach { Class.forName(it, true, rewritten.loader) }
        }
        assertNull(initialized.error, "every class verified and initialized: $initialized")

        for (module in listOf(rewritten, plain)) {
            assertNull(module.start().error)
            repeat(25) { module.context.timers.forEach { timer -> assertNull(module.call { timer.run() }.error) } }
            val facts = listOf(
                GameFact.BlockBroken("Mimosa", Place("overworld", 1.5, 64.0, 2.5), "diamond_ore", false),
                GameFact.Chat("Bartok", Place("overworld", 0.5, 64.0, 0.5), "score"),
                GameFact.Chat("Mimosa", Place("overworld", 0.5, 64.0, 0.5), "loup"),
            )
            for (fact in facts) {
                module.context.handlers.filter { (type, _) -> type.isInstance(fact) }.forEach { (_, handler) -> assertNull(module.call { handler.accept(fact) }.error) }
            }
        }
        val (call, table) = rewritten.invoke("table")
        assertNull(call.error, "$call")
        assertEquals(plain.invoke("table").second, table)
        assertEquals(plain.invoke("greeting", "sévère").second, rewritten.invoke("greeting", "sévère").second)
        assertEquals(plain.context.logs.toList(), rewritten.context.logs.toList())
        assertEquals(plain.context.storage, rewritten.context.storage)
        assertNull(rewritten.meter.tripped())
    }

    @Test
    fun `a module rich in what Java holds behaves the same rewritten`() {
        val source = Rewrites.source(
            "Rich",
            """
            enum Suit { HEARTS, SPADES }
            sealed interface Shape permits Circle, Square { }
            record Circle(double r) implements Shape { }
            record Square(double side) implements Shape { }
            record Team(String name, List<String> members, Map<String, Integer> scores) { }
            interface Greeter { String greet(String who); default String twice(String who) { return greet(who) + greet(who); } }
            static double area(Shape s) { return switch (s) { case Circle c -> Math.PI * c.r() * c.r(); case Square q -> q.side() * q.side(); }; }
            static int fib(int n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }
            """.trimIndent(),
            """
            List<String> names = new ArrayList<>(List.of("Bartok", "Mimosa", "Josiane"));
            Map<String, Integer> scores = new TreeMap<>();
            for (String name : names) scores.merge(name, name.length(), Integer::sum);
            scores.computeIfAbsent("Nobody", k -> 0);
            scores.compute("Bartok", (k, v) -> v == null ? 1 : v + 10);
            m.log("scores " + scores + " " + scores.entrySet() + " " + scores.keySet());
            Team team = new Team("red", names, scores);
            m.log(team.toString() + " " + team.equals(new Team("red", names, scores)) + " " + (team.hashCode() == new Team("red", names, scores).hashCode()));
            m.log(String.valueOf(Optional.of(names)) + Objects.toString(null) + Objects.toString(null, "none") + Optional.empty());
            m.log(names.stream().map(String::toUpperCase).sorted(Comparator.reverseOrder()).collect(Collectors.joining(", ", "<", ">")));
            m.log(String.join("|", names) + String.join("-", "a", "b", "c") + names.stream().collect(Collectors.joining()));
            m.log(IntStream.rangeClosed(1, 10).mapToObj(Integer::toString).collect(Collectors.joining(",")) + " " + IntStream.range(0, 5).sum());
            m.log(Arrays.toString(new int[] { 3, 1, 2 }) + Arrays.toString(new double[] { 1.5 }) + Arrays.toString(new Object[] { names, 1 }) + Arrays.deepToString(new Object[] { new int[] { 1 }, new Object[] { "x" } }));
            m.log(String.format("%5.2f|%-6s|%03d|%,d", Math.E, "ok", 7, 1234567) + "%s and %s".formatted(names, scores));
            StringBuilder sb = new StringBuilder("start:");
            sb.append(1).append('c').append(2L).append(3.5f).append(4.25).append(true).append((Object) names).append(new char[] { 'x', 'y' }).appendCodePoint(0x1F600);
            sb.insert(0, '[').insert(1, 42).insert(0, (Object) scores).append("tail", 1, 3).replace(0, 2, "AB");
            sb.setLength(sb.length() - 1);
            sb.ensureCapacity(100);
            sb.repeat('-', 3).repeat("ab", 2);
            m.log(sb.toString() + " " + sb.length());
            m.log("x".repeat(3) + "a\nb".indent(2) + "ab".replace("b", "cd") + "ab".concat("cd") + "a-b".replace("", "."));
            List<Integer> numbers = new LinkedList<>();
            for (int i = 0; i < 10; i++) numbers.add(i);
            numbers.subList(2, 5).clear();
            numbers.subList(0, 2).add(99);
            ListIterator<Integer> it = numbers.listIterator();
            while (it.hasNext()) if (it.next() % 2 == 0) it.add(-1);
            m.log(numbers + " " + numbers.subList(1, 4) + " " + new ArrayList<>(numbers.subList(0, 3)));
            Deque<String> deque = new ArrayDeque<>(8);
            deque.push("a"); deque.offerFirst("b"); deque.offerLast("c"); deque.addFirst("d"); deque.addLast("e"); deque.offer("f");
            m.log(deque + " " + new PriorityQueue<>(List.of(3, 1, 2)).peek() + " " + new PriorityQueue<Integer>(4, Comparator.reverseOrder()));
            Map<String, Integer> lru = new LinkedHashMap<>(16, 0.75f, true);
            lru.put("a", 1); lru.put("b", 2); lru.get("a"); lru.putIfAbsent("c", 3); lru.putAll(Map.of("d", 4));
            Set<String> set = new HashSet<>(8, 0.5f); set.add("x"); set.addAll(List.of("y", "x")); Collections.addAll(set, "z", "x");
            m.log(lru + " " + new TreeSet<>(set) + " " + HashMap.newHashMap(4).isEmpty() + LinkedHashSet.newLinkedHashSet(2).size());
            Stream.Builder<String> b = Stream.builder(); b.add("s1").add("s2");
            m.log(b.build().collect(Collectors.toList()) + " " + IntStream.builder().add(1).add(2).build().sum());
            StringJoiner joiner = new StringJoiner(",", "{", "}"); joiner.add("a").add("b"); joiner.merge(new StringJoiner("+").add("c"));
            m.log(joiner + " " + joiner.length());
            int[] copy = Arrays.copyOf(new int[] { 1, 2, 3 }, 5); String[] range = Arrays.copyOfRange(new String[] { "a", "b", "c" }, 1, 3);
            m.log(Arrays.toString(copy) + Arrays.toString(range) + Arrays.toString(Arrays.copyOf(new Object[] { "o" }, 2, String[].class)));
            Greeter greeter = who -> "hi " + who + ";";
            Greeter anonymous = new Greeter() { public String greet(String who) { return "yo " + who; } };
            m.log(greeter.twice("you") + anonymous.greet("me") + area(new Circle(1)) + area(new Square(2)) + Suit.SPADES + " " + Suit.valueOf("HEARTS").ordinal());
            Function<Integer, List<Integer>> sized = ArrayList::new;
            Supplier<Map<String, String>> map = HashMap::new;
            BiFunction<String, Integer, String> repeat = String::repeat;
            IntFunction<String[]> array = String[]::new;
            m.log(sized.apply(3).size() + " " + map.get().size() + " " + repeat.apply("ab", 2) + " " + array.apply(2).length + " " + fib(15));
            String text = switch (Suit.HEARTS) { case HEARTS -> "h"; case SPADES -> "s"; };
            int caught = 0;
            for (int i = 0; i < 20; i++) { try { Integer.parseInt("x" + i); } catch (NumberFormatException e) { caught++; } finally { caught += 0; } }
            char[] chars = new char[3]; Arrays.fill(chars, 'z');
            m.log(text + caught + new String(chars) + new StringBuilder((CharSequence) "cs").append((CharSequence) sb, 0, 2) + Objects.hash(1, 2) + names.hashCode());
            """.trimIndent(),
        )
        val rewritten = Rewrites.rewritten("Rich", source)
        val plain = Rewrites.plain("Rich", source)
        val ran = rewritten.start(budgetNanos = 10_000_000_000L)
        assertNull(ran.error, "$ran")
        assertNull(ran.tripped, "$ran")
        assertNull(plain.start().error)
        assertEquals(plain.context.logs.toList(), rewritten.context.logs.toList())
        assertTrue(rewritten.context.logs.size >= 15, "${rewritten.context.logs}")
    }

    // ── Each insertion ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `each method enters, exits, ticks back and at each handler, without a frame recomputed`() {
        val classes = Rewrites.compile(
            "Shape",
            Rewrites.source(
                "Shape",
                "static int loop(int n) { int s = 0; for (int i = 0; i < n; i++) { s += i; } return s; } " +
                    "static int guarded(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; } } " +
                    "static int twoReturns(int n) { if (n > 0) return 1; return 2; }",
                "m.log(\"\" + loop(3) + guarded(\"x\") + twoReturns(1));",
            ),
        )
        val rewritten = Instrumenter.rewrite("Shape", classes)
        val before = Calls(classes.getValue("Shape"))
        val after = Calls(rewritten.getValue("Shape"))
        assertEquals(before.frames, after.frames.filterKeys { it in before.frames }, "the same frames, none added or recomputed")
        for (method in before.methods.keys) assertEquals(1, after.meterCalls(method, "enter"), method)
        assertEquals(1, after.meterCalls("loop(I)I", "exit"))
        assertEquals(1, after.meterCalls("loop(I)I", "tick"), "one backward jump")
        assertEquals(2, after.meterCalls("twoReturns(I)I", "exit"))
        assertEquals(0, after.meterCalls("twoReturns(I)I", "tick"), "no backward jump")
        assertEquals(1, after.meterCalls("guarded(Ljava/lang/String;)I", "tick"), "the entry of the handler")

        val holder = Calls(rewritten.getValue("Shape" + Built.METER_SUFFIX))
        assertEquals(listOf("${Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC} ${Built.METER_FIELD} L${Meter::class.java.name.replace('.', '/')};"), holder.fields)
        assertTrue(holder.methods.isEmpty(), "no code in the holder: nothing runs before the host sets its meter")
    }

    @Test
    fun `arrays, capacities, growth and texts go through Guarded`() {
        val classes = Rewrites.compile(
            "Sites",
            Rewrites.source(
                "Sites",
                "",
                """
                int[] a = new int[4]; String[] s = new String[2];
                List<Object> l = new ArrayList<>(8); l.add(a);
                Map<String, Object> map = new HashMap<>(4, 0.75f); map.put("k", s);
                StringBuilder sb = new StringBuilder(4).append("x");
                m.log("x" + l + map + sb + new LinkedHashMap<String, String>(2, 0.75f, true));
                """.trimIndent(),
            ),
        )
        val calls = Calls(Instrumenter.rewrite("Sites", classes).getValue("Sites")).methods.getValue("start(Lio/github/clemsix6/narrator2/forge/api/ModuleContext;)V")
        val guardedCalls = calls.filter { it.startsWith("io/github/clemsix6/narrator2/forge/rt/Guarded.") }.map { it.substringAfter("Guarded.") }
        assertEquals(2, guardedCalls.count { it == "array(I)I" })
        assertEquals(4, guardedCalls.count { it == "capacity(I)I" }, "$guardedCalls")
        assertTrue("add(Ljava/util/Collection;Ljava/lang/Object;)Z" in guardedCalls)
        assertTrue("put(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" in guardedCalls)
        assertTrue("append(Ljava/lang/StringBuilder;Ljava/lang/String;)Ljava/lang/StringBuilder;" in guardedCalls)
        assertTrue(calls.none { it.startsWith("java/util/List.add") || it.startsWith("java/util/Map.put") || it.startsWith("java/lang/StringBuilder.append") })
        assertTrue(Calls(Instrumenter.rewrite("Sites", classes).getValue("Sites")).bootstraps.all { it.owner != "java/lang/invoke/StringConcatFactory" })
    }

    @Test
    fun `a method reference to the JDK goes through a bridge, the module's own lambdas do not`() {
        val classes = Rewrites.compile(
            "Refs",
            Rewrites.source("Refs", "", "List<Object> l = new ArrayList<>(); Consumer<Object> add = l::add; Function<Object, String> text = Object::toString; Runnable mine = () -> m.log(\"x\"); add.accept(1); mine.run();"),
        )
        val after = Calls(Instrumenter.rewrite("Refs", classes).getValue("Refs"))
        assertTrue(after.handles.isNotEmpty() && after.handles.all { it.owner == "Refs" }, "every implementation is the module's own: ${after.handles}")
        val bridges = after.methods.keys.filter { it.startsWith("forge\$bridge\$") }
        assertEquals(2, bridges.size, "l::add and Object::toString: ${after.methods.keys}")
        assertTrue(bridges.all { after.meterCalls(it, "enter") == 1 }, "a bridge is metered")
        assertTrue(after.methods.values.flatten().any { it == "io/github/clemsix6/narrator2/forge/rt/Guarded.add(Ljava/util/Collection;Ljava/lang/Object;)Z" })
        assertTrue(after.methods.values.flatten().any { it == "io/github/clemsix6/narrator2/forge/rt/Guarded.toString(Ljava/lang/Object;)Ljava/lang/String;" })
    }

    // ── The members wrapped ─────────────────────────────────────────────────────────────────────────────────────

    /** A class that calls each member of [Instrumenter.rewritten] once, with typed values: the rewriting replaces each call. */
    private fun everyRewrittenMember(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "Every", null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "all", "()V", null, null)
        method.visitCode()
        for (member in Instrumenter.rewritten) {
            val type = Class.forName(member.owner.replace('/', '.'))
            val arguments = Type.getArgumentTypes(member.descriptor)
            if (member.name == "<init>") {
                method.visitTypeInsn(Opcodes.NEW, member.owner)
                method.visitInsn(Opcodes.DUP)
            }
            val isStatic = member.name != "<init>" && (type.methods.asList() + type.declaredMethods)
                .first { it.name == member.name && Type.getMethodDescriptor(it) == member.descriptor }.let { Modifier.isStatic(it.modifiers) }
            if (member.name != "<init>" && !isStatic) {
                method.visitInsn(Opcodes.ACONST_NULL)
                method.visitTypeInsn(Opcodes.CHECKCAST, member.owner)
            }
            for (argument in arguments) {
                when (argument.sort) {
                    Type.LONG -> method.visitInsn(Opcodes.LCONST_0)
                    Type.FLOAT -> method.visitInsn(Opcodes.FCONST_0)
                    Type.DOUBLE -> method.visitInsn(Opcodes.DCONST_0)
                    Type.OBJECT, Type.ARRAY -> {
                        method.visitInsn(Opcodes.ACONST_NULL)
                        method.visitTypeInsn(Opcodes.CHECKCAST, argument.internalName)
                    }
                    else -> method.visitInsn(Opcodes.ICONST_0)
                }
            }
            val opcode = when {
                member.name == "<init>" -> Opcodes.INVOKESPECIAL
                isStatic -> Opcodes.INVOKESTATIC
                type.isInterface -> Opcodes.INVOKEINTERFACE
                else -> Opcodes.INVOKEVIRTUAL
            }
            method.visitMethodInsn(opcode, member.owner, member.name, member.descriptor, type.isInterface)
            val returned = if (member.name == "<init>") Type.getObjectType(member.owner) else Type.getReturnType(member.descriptor)
            if (returned.size == 2) method.visitInsn(Opcodes.POP2) else if (returned.size == 1) method.visitInsn(Opcodes.POP)
        }
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `every member it wraps exists in the JDK, has its twin in Guarded, and the result verifies`() {
        assertTrue(Instrumenter.rewritten.size > 100, "${Instrumenter.rewritten.size}")
        val rewritten = Instrumenter.rewrite("Every", mapOf("Every" to everyRewrittenMember()))
        val calls = Calls(rewritten.getValue("Every")).methods.getValue("all()V")
        val toGuarded = calls.filter { it.startsWith("io/github/clemsix6/narrator2/forge/rt/Guarded.") }.map { it.substringAfter("Guarded.") }
        val missing = toGuarded.filter { it !in guarded }
        assertEquals(emptyList(), missing, "Guarded has a public static method for each")
        val unwrapped = Instrumenter.rewritten.filter { it.name != "<init>" }.map { "${it.owner}.${it.name}${it.descriptor}" }.filter { it in calls }
        assertEquals(emptyList(), unwrapped, "every call replaced")
        val module = LoadedModule("Every", rewritten, metered = true)
        val verified = module.call { Class.forName("Every", true, module.loader) }
        assertNull(verified.error, "the verifier accepts it: $verified")
    }

    @Test
    fun `it wraps every capacity the guard permits, read from the one list`() {
        val wrapped = Instrumenter.rewritten.map { "${it.owner}.${it.name}" }.toSet()
        SandboxPolicy.CAPACITY_METHODS.forEach { assertTrue(it in wrapped, "$it is wrapped") }
        SandboxPolicy.CAPACITY_CTOR_TYPES.forEach { owner ->
            Class.forName(owner.replace('/', '.')).constructors.filter { it.parameterCount > 0 && it.parameterTypes[0] == Integer.TYPE }.forEach { constructor ->
                val descriptor = Type.getConstructorDescriptor(constructor)
                assertTrue(Instrumenter.rewritten.any { it.owner == owner && it.name == "<init>" && it.descriptor == descriptor }, "$constructor is wrapped")
            }
        }
        val builder = StringBuilder::class.java.methods.filter { (it.name == "append" || it.name == "insert") && !it.isBridge && it.returnType == StringBuilder::class.java }
            .filter { method -> method.parameterTypes.none { it == StringBuffer::class.java } }
        builder.forEach { method ->
            assertTrue(Instrumenter.rewritten.any { it.owner == "java/lang/StringBuilder" && it.name == method.name && it.descriptor == Type.getMethodDescriptor(method) }, "$method is wrapped")
        }
    }

    // ── What it refuses ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a module's class may not be a collection, a map or a CharSequence, refused by the guard and by the rewriting`() {
        val lying = "static final class Lie implements CharSequence { public int length() { return 500_000_000; } public char charAt(int i) { return 'x'; } " +
            "public CharSequence subSequence(int a, int b) { return this; } public String toString() { return \"x\"; } }"
        val modules = mapOf(
            "LyingText" to Rewrites.source("LyingText", lying, "new StringBuilder(new Lie()); new StringBuilder().append(new Lie()).insert(0, new Lie()); m.log(new Lie().chars().count() + \"\");"),
            "LyingBag" to Rewrites.source("LyingBag", "interface Bag extends Collection<Integer> { }", "Set<Integer> s = new HashSet<>();"),
            "LyingTable" to Rewrites.source("LyingTable", "interface Table extends Map<Integer, Integer> { } static abstract class Big implements Table { }", ""),
        )
        modules.forEach { (name, source) ->
            val classes = Rewrites.compile(name, source)
            val violations = BytecodeGuard().check(classes)
            assertTrue(violations.isNotEmpty() && violations.all { "may not be a collection, a map or a CharSequence" in it.reason }, "$name: $violations")
            // Defense in depth: without the guard, the rewriting refuses it too.
            val refused = assertFailsWith<RewriteRefused> { Instrumenter.rewrite(name, classes) }
            assertTrue("may not be a" in refused.message!!, refused.message)
        }
    }

    @Test
    fun `it refuses a multi-dimensional array, the holder's name and the runtime's names`() {
        assertFailsWith<RewriteRefused> { Instrumenter.rewrite("Grid", Rewrites.compile("Grid", Rewrites.source("Grid", "", "int[][] g = new int[3][3]; m.log(\"\" + g.length);"))) }
        val classes = Rewrites.compile("Named", Rewrites.source("Named", "", "m.log(\"x\");"))
        assertFailsWith<RewriteRefused> { Instrumenter.rewrite("Named", classes + ("Named" + Built.METER_SUFFIX to classes.getValue("Named"))) }
        for (target in listOf("Named" + Built.METER_SUFFIX, "io/github/clemsix6/narrator2/forge/rt/Meter")) {
            val forged = ClassWriter(0).apply {
                visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "Named", null, "java/lang/Object", null)
                visitMethod(Opcodes.ACC_STATIC, "touch", "()V", null, null).apply {
                    visitCode()
                    visitFieldInsn(Opcodes.GETSTATIC, target, "m", "Ljava/lang/Object;")
                    visitInsn(Opcodes.POP)
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(1, 0)
                    visitEnd()
                }
                visitEnd()
            }.toByteArray()
            assertFailsWith<RewriteRefused> { Instrumenter.rewrite("Named", mapOf("Named" to forged)) }
        }
        // A handler inside its own range: a tripped meter would throw there again and again, without end.
        val endless = ClassWriter(0).apply {
            visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "Endless", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_STATIC, "spin", "()V", null, null).apply {
                val start = org.objectweb.asm.Label()
                val end = org.objectweb.asm.Label()
                visitCode()
                visitTryCatchBlock(start, end, start, null)
                visitLabel(start)
                visitInsn(Opcodes.ATHROW)
                visitLabel(end)
                visitMaxs(1, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        assertEquals("an exception handler that covers itself", assertFailsWith<RewriteRefused> { Instrumenter.rewrite("Endless", mapOf("Endless" to endless)) }.message)
    }

    // ── What it costs ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `its overhead is bounded`() {
        val threads = ManagementFactory.getThreadMXBean()
        val source = Rewrites.source(
            "Cost",
            """
            static int step(int i) { return i & 7; }
            public static long work(int n) { long s = 0; for (int i = 0; i < n; i++) s += step(i); return s; }
            public static int typical(int n) {
                int total = 0;
                for (int round = 0; round < n; round++) {
                    List<String> names = new ArrayList<>();
                    for (int i = 0; i < 20; i++) names.add("p" + i);
                    Map<String, Integer> scores = new HashMap<>();
                    for (String name : names) scores.merge(name, name.length(), Integer::sum);
                    String table = names.stream().filter(x -> x.length() > 2).map(x -> x + "=" + scores.get(x)).collect(Collectors.joining(", "));
                    total += table.length() + new StringBuilder(table).append(round).length();
                }
                return total;
            }
            """.trimIndent(),
            "",
        )
        val classes = Rewrites.compile("Cost", source)
        val rewrittenClasses = Instrumenter.rewrite("Cost", classes)
        repeat(20) { Instrumenter.rewrite(ForgeSamples.CLASS, ForgeSamples.classes) }
        val witnessStart = System.nanoTime()
        val witness = Instrumenter.rewrite(ForgeSamples.CLASS, ForgeSamples.classes)
        val witnessMillis = (System.nanoTime() - witnessStart) / 1e6
        val rewritten = LoadedModule("Cost", rewrittenClasses, metered = true)
        val plain = LoadedModule("Cost", classes, metered = false)

        fun cpu(module: LoadedModule, method: String, n: Int): Long {
            var nanos = 0L
            val call = module.call(budgetNanos = 60_000_000_000L, allocation = Long.MAX_VALUE) {
                val target = module.type.getMethod(method, Int::class.javaPrimitiveType)
                repeat(5) { target.invoke(null, n) }
                val before = threads.currentThreadCpuTime
                repeat(10) { target.invoke(null, n) }
                nanos = threads.currentThreadCpuTime - before
            }
            assertNull(call.error, "$call")
            return nanos
        }

        val loops = 20_000_000
        val plainLoop = cpu(plain, "work", loops)
        val meteredLoop = cpu(rewritten, "work", loops)
        val plainTypical = cpu(plain, "typical", 3_000)
        val meteredTypical = cpu(rewritten, "typical", 3_000)
        val perCall = (meteredLoop - plainLoop) / (10.0 * loops)
        val typicalRatio = meteredTypical.toDouble() / plainTypical
        println(
            "[overhead] rewriting the witness (9 classes, warm): ${"%.2f".format(witnessMillis)} ms, " +
                "${ForgeSamples.classes.values.sumOf { it.size }} -> ${witness.values.sumOf { it.size }} bytes; " +
                "loop with a call per turn: ${"%.1f".format(perCall)} ns per turn (x${"%.1f".format(meteredLoop.toDouble() / plainLoop)}); " +
                "typical code (lists, maps, streams, texts): x${"%.2f".format(typicalRatio)}, " +
                "${"%.1f".format(meteredTypical / 30_000 / 1e3)} µs a round instead of ${"%.1f".format(plainTypical / 30_000 / 1e3)} (CPU time)",
        )
        assertTrue(perCall < 100, "a few nanoseconds per call: $perCall")
        assertTrue(typicalRatio < 5, "typical code is barely slower: x$typicalRatio")
    }
}
