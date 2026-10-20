package io.github.clemsix6.narrator2.forge.sandbox

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Limits
import io.github.clemsix6.narrator2.testing.BusyMachine
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The runtime battery of the sandbox's spike (`.research/spikes/m5`), and the attacks of the design review (finding 1)
 * and of the M5-01 handoff (findings 1 to 5), through ECJ, the rewriting and the meter, as the host calls a module:
 * each one is stopped, a single call of the JDK before it allocates. Open: `HostBatteryTest` runs the same battery
 * through the real loader and host (M5-05) by overriding [run].
 *
 * A case about the meter's time is `timed`: it has the game's budget. Any other has a busy machine's patience
 * ([BusyMachine]): its limit, never the clock, is what stops it; were that limit gone, the time or the allocation
 * would, with another fault.
 */
open class RewriteBatteryTest {
    /** Starts the rewritten module [name] ([classes]) as the host calls a module: here, the test harness. */
    internal open fun run(name: String, classes: Map<String, ByteArray>, timed: Boolean): MeteredCall =
        LoadedModule(name, classes, metered = true).start(budgetNanos = if (timed) ForgeLimits.CALL_NANOS else BusyMachine.PATIENT_NANOS)

    /** Compiles, checks that the guard lets it through (the rewriting is then the barrier), rewrites and starts a module. */
    private fun start(name: String, members: String = "", body: String, timed: Boolean = false): MeteredCall {
        val classes = Rewrites.compile(name, Rewrites.source(name, members, body))
        if ("nCopies" !in body) assertEquals(emptyList(), BytecodeGuard().check(classes), "$name passes the guard")
        return run(name, Instrumenter.rewrite(name, classes), timed)
    }

    /** Stopped by a limit of `Guarded` ([message]) before the JDK allocated. */
    private fun assertGuarded(call: MeteredCall, message: String) {
        assertTrue(call.fault?.startsWith("ForgeLimit: $message") == true, "stopped by « $message »: $call")
        if (call.allocated >= 0) assertTrue(call.allocated < 4L shl 20, "stopped before it allocated: $call")
    }

    /** Stopped by the meter: [why], at the module's line, sticky. */
    private fun assertTripped(call: MeteredCall, why: String, module: String) {
        assertTrue(call.fault?.startsWith("ForgeLimit") == true, "stopped by the meter: $call")
        val tripped = call.tripped ?: error("the meter tripped: $call")
        assertTrue(tripped.startsWith(why), "tripped by « $why »: $call")
        assertTrue("at $module.java:" in tripped, "the module's line: $tripped")
    }

    // ── The spike's runtime battery ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an endless loop is stopped at 20 ms`() {
        val call = start("Loop", body = "while (true) { }", timed = true)
        assertTripped(call, "time budget", "Loop")
        assertTrue(call.millis >= ForgeLimits.CALL_MILLIS && call.millis < 2_000, "stopped at about 20 ms: $call")
    }

    @Test
    fun `a recursion, a lambda's recursion and a recursion across two classes are stopped at 64 frames`() {
        assertTripped(start("Recursion", "static int f(int x) { return f(x + 1) + 1; }", "f(0);"), "call depth over ${Limits.MAX_DEPTH}", "Recursion")
        assertTripped(start("LambdaRecursion", "static Runnable r;", "r = () -> r.run(); r.run();"), "call depth over ${Limits.MAX_DEPTH}", "LambdaRecursion")
        val across = start(
            "Across",
            "static final class Ping { static int go(int n) { return Pong.go(n + 1); } } static final class Pong { static int go(int n) { return Ping.go(n + 1); } }",
            "Ping.go(0);",
        )
        assertTripped(across, "call depth over ${Limits.MAX_DEPTH}", "Across")
    }

    @Test
    fun `a giant array is refused before it is made`() {
        assertGuarded(start("BigArray", body = "long[] a = new long[100_000_000]; m.log(\"\" + a.length);"), "an array of 100000000 elements")
        assertGuarded(start("BigObjects", body = "Object[] a = new Object[500_000_000]; m.log(\"\" + a.length);"), "an array of 500000000 elements")
    }

    @Test
    fun `a list, a map, a builder and a doubling concatenation stop growing at their limit`() {
        assertGuarded(start("ListGrowth", body = "List<Object> l = new ArrayList<>(); while (true) l.add(new Object());"), "a collection over 10000 elements")
        assertGuarded(start("MapGrowth", body = "Map<Integer, Integer> l = new HashMap<>(); int i = 0; while (true) l.put(i++, i);"), "a collection over 10000 elements")
        assertGuarded(start("BuilderGrowth", body = "StringBuilder sb = new StringBuilder(); while (true) sb.append(\"xxxxxxxxxx\");"), "a text over 65536 characters")
        assertGuarded(start("ConcatDoubling", body = "String s = \"x\"; while (true) s = s + s;"), "a text over 65536 characters")
    }

    @Test
    fun `a finally cannot swallow the meter's error, and a loop in a finally does not outlive the meter`() {
        // The handler's entry ticks: a tripped meter throws again there, before the finally's return can swallow it.
        assertTripped(start("Swallow", body = "try { while (true) { } } finally { return; }", timed = true), "time budget", "Swallow")
        assertTripped(start("FinallyLoop", body = "try { while (true) { } } finally { while (true) { } }", timed = true), "time budget", "FinallyLoop")
    }

    @Test
    fun `catch Exception does not swallow the meter's error`() {
        val call = start("CatchException", body = "try { while (true) { } } catch (Exception e) { m.log(\"caught\"); }", timed = true)
        assertTripped(call, "time budget", "CatchException")
        assertEquals(emptyList(), call.logs)
    }

    @Test
    fun `an exception of the module goes out as it is`() {
        val call = start("Throws", body = "throw new IllegalStateException(\"boom\");")
        assertTrue(call.fault?.startsWith("IllegalStateException: boom") == true, "$call")
        assertNull(call.tripped)
    }

    @Test
    fun `allocating in a loop is stopped at 16 MiB`() {
        val call = start("AllocChurn", "static long sink;", "for (;;) { long[] a = new long[65_000]; sink += a.length; }")
        assumeTrue(call.allocated >= 0, "the JVM measures allocation")
        assertTripped(call, "allocation budget", "AllocChurn")
        val tick = 65_000 * 8L + 16
        val slack = ForgeLimits.METER_CHECK_EVERY * tick + (1L shl 20)
        assertTrue(call.allocated >= ForgeLimits.CALL_ALLOCATION_BYTES && call.allocated <= ForgeLimits.CALL_ALLOCATION_BYTES + slack, "stopped at 16 MiB: $call")
    }

    @Test
    fun `5,000 caught exceptions do not trip the depth`() {
        val members = "static int parse(String s) { return Integer.parseInt(s); } static int deep(int n, String s) { return n == 0 ? parse(s) : deep(n - 1, s); }"
        val body = "int bad = 0; for (int i = 0; i < 5000; i++) { try { deep(10, \"x\" + i); } catch (NumberFormatException e) { bad++; } } m.log(\"bad \" + bad);"
        val call = Rewrites.rewritten("CatchDrift", members, body).start(budgetNanos = 10_000_000_000L)
        assertNull(call.error, "$call")
        assertNull(call.tripped, "no false positive: $call")
        assertEquals(listOf("bad 5000"), call.logs)
        val plain = Rewrites.plain("CatchDrift", members, body).start()
        println("[battery] 5,000 exceptions caught 11 frames deep: ${"%.1f".format(call.millis)} ms rewritten, ${"%.1f".format(plain.millis)} ms plain (budget ${ForgeLimits.CALL_MILLIS} ms)")
    }

    // ── One call of the JDK that allocates at once (design review, finding 1; M5-01 handoff, finding 1) ──────────

    @Test
    fun `a capacity asked at once is refused before the JDK reserves it`() {
        val capacities = listOf(
            "new StringBuilder().ensureCapacity(500_000_000);",
            "new StringBuilder(500_000_000);",
            "new ArrayList<Object>(500_000_000);",
            "new ArrayList<Object>().ensureCapacity(500_000_000);",
            "new ArrayDeque<Object>(500_000_000);",
            "new PriorityQueue<Integer>(500_000_000);",
            "new PriorityQueue<Integer>(500_000_000, Comparator.naturalOrder());",
            "new HashMap<Integer, Integer>(500_000_000).put(1, 1);",
            "new HashMap<Integer, Integer>(500_000_000, 0.75f).put(1, 1);",
            "new LinkedHashMap<Integer, Integer>(500_000_000).put(1, 1);",
            "new LinkedHashMap<Integer, Integer>(500_000_000, 0.75f).put(1, 1);",
            "new LinkedHashMap<Integer, Integer>(500_000_000, 0.75f, true).put(1, 1);",
            "new HashSet<Integer>(500_000_000).add(1);",
            "new HashSet<Integer>(500_000_000, 0.75f).add(1);",
            "new LinkedHashSet<Integer>(500_000_000).add(1);",
            "new LinkedHashSet<Integer>(500_000_000, 0.75f).add(1);",
            "HashMap.<Integer, Integer>newHashMap(500_000_000).put(1, 1);",
            "LinkedHashMap.<Integer, Integer>newHashMap(500_000_000).put(1, 1);",
            "LinkedHashMap.<Integer, Integer>newLinkedHashMap(500_000_000).put(1, 1);",
            "HashSet.<Integer>newHashSet(500_000_000).add(1);",
            "LinkedHashSet.<Integer>newLinkedHashSet(500_000_000).add(1);",
            "IntFunction<List<Integer>> make = ArrayList::new; make.apply(500_000_000);",
            "IntFunction<StringBuilder> make = StringBuilder::new; make.apply(500_000_000);",
        )
        capacities.forEachIndexed { i, body -> assertGuarded(start("Capacity$i", body = body), "a capacity of 500000000") }
    }

    @Test
    fun `texts, copies and ranges made in one call are bounded`() {
        val cases = listOf(
            "new StringBuilder().setLength(500_000_000);" to "a text over 65536 characters",
            "String s = \"abcd\".repeat(200_000_000);" to "a text over 65536 characters",
            "new StringBuilder().repeat('x', 20_000_000);" to "a text over 65536 characters",
            "new StringBuilder().repeat(\"ab\", 20_000_000);" to "a text over 65536 characters",
            "String s = \"a\".indent(20_000_000);" to "a text over 65536 characters",
            "String s = \"a\\n\".repeat(30_000).indent(10);" to "a text over 65536 characters",
            "String s = \"a\".repeat(65_536).replace(\"a\", \"b\".repeat(30_000));" to "a text over 65536 characters",
            "String s = \"ab\".replace(\"\", \"c\".repeat(60_000));" to "a text over 65536 characters",
            "String s = String.format(\"%250000000d\", 1);" to "a format width or precision over 3 digits",
            "String s = \"%.1000f\".formatted(1.0);" to "a format width or precision over 3 digits",
            "String s = String.format(\"%1\$999s\".repeat(100), \"x\");" to "a text over 65536 characters",
            "int[] b = Arrays.copyOf(new int[1], 500_000_000);" to "an array of 500000000 elements",
            "int[] b = Arrays.copyOfRange(new int[1], 0, 500_000_000);" to "an array of 500000000 elements",
            "String[] b = Arrays.copyOf(new String[1], 500_000_000);" to "an array of 500000000 elements",
            "String[] b = Arrays.copyOf(new Object[1], 500_000_000, String[].class);" to "an array of 500000000 elements",
            "long n = IntStream.range(0, Integer.MAX_VALUE).sum();" to "a range of 2147483647 numbers",
            "long n = LongStream.rangeClosed(0, Long.MAX_VALUE).sum();" to "a range of",
            "List<String> l = Collections.nCopies(Integer.MAX_VALUE, \"x\"); m.log(\"\" + l.hashCode());" to "a collection over 10000 elements",
        )
        cases.forEachIndexed { i, (body, message) -> assertGuarded(start("OneCall$i", body = body), message) }
    }

    @Test
    fun `the text of a whole collection is made within the limit`() {
        val big = "String big = \"x\".repeat(65_536); List<String> l = new ArrayList<>(); for (int i = 0; i < 10_000; i++) l.add(big);"
        val members = "record Box(List<String> items) { }"
        val texts = listOf(
            "String s = l.toString();",
            "String s = \"Scores : \" + l;",
            "Object o = l; String s = o.toString();",
            "String s = String.valueOf(l);",
            "String s = Objects.toString(l);",
            "String s = Objects.toString(l, \"none\");",
            "new StringBuilder().append((Object) l);",
            "new StringBuilder().insert(0, (Object) l);",
            "String s = String.join(\"\", l);",
            "String s = String.join(\",\", l.toArray(new String[0]));",
            "String s = l.stream().collect(Collectors.joining());",
            "String s = l.stream().collect(Collectors.joining(\",\", \"[\", \"]\"));",
            "String s = Arrays.toString(l.toArray());",
            "String s = Arrays.deepToString(new Object[] { l });",
            "String s = String.format(\"%s\", l);",
            "String s = \"%s\".formatted(l);",
            "String s = new Box(l).toString();",
            "String s = Map.of(\"scores\", l).toString();",
            "String s = Optional.of(l).toString();",
            "String s = List.of(l, l).toString();",
            "List<String> t = Stream.of(l).map(Object::toString).toList();",
            "List<String> t = Stream.of(l).map(String::valueOf).toList();",
            "StringBuilder sb = new StringBuilder(); l.forEach(sb::append);",
            "StringJoiner j = new StringJoiner(\",\"); l.forEach(j::add);",
        )
        texts.forEachIndexed { i, body -> assertGuarded(start("WholeText$i", members, big + body), "a text over 65536 characters") }
    }

    @Test
    fun `a delimiter, a joiner and a concatenation that doubles stop at the limit`() {
        assertGuarded(start("Joiner", body = "StringJoiner j = new StringJoiner(\"x\".repeat(60_000)); while (true) j.add(\"\");"), "a text over 65536 characters")
        assertGuarded(start("Concat", body = "String s = \"x\"; while (true) s = s.concat(s);"), "a text over 65536 characters")
        assertGuarded(start("JoinEmpty", body = "List<String> l = new ArrayList<>(); for (int i = 0; i < 10_000; i++) l.add(\"\"); String s = String.join(\"y\".repeat(1_000), l);"), "a text over 65536 characters")
    }

    // ── What grows without an `add` the rewriting sees ──────────────────────────────────────────────────────────

    @Test
    fun `a method reference grows a collection through the same guard`() {
        assertGuarded(start("RefAdd", body = "List<Integer> l = new ArrayList<>(); IntStream.range(0, 20_000).boxed().forEach(l::add);"), "a collection over 10000 elements")
        assertGuarded(start("RefPut", body = "Map<Integer, Integer> l = new HashMap<>(); BiFunction<Integer, Integer, Integer> put = l::put; for (int i = 0; ; i++) put.apply(i, i);"), "a collection over 10000 elements")
    }

    @Test
    fun `a sub-list, a list iterator and a stream builder count against their limit`() {
        // Each view adds at the end of the list (no shift of what is there): the size limit is what stops it, never the
        // time (inserting at the head of an ArrayList is O(n) a time, and 10,000 of them could reach 20 ms on a busy machine).
        assertGuarded(start("SubList", body = "List<Integer> l = new ArrayList<>(); while (true) l.subList(l.size(), l.size()).add(1);"), "a collection over 10000 elements")
        assertGuarded(
            start("SubSubList", body = "List<Integer> l = new ArrayList<>(List.of(1, 2, 3)); while (true) l.subList(l.size() - 2, l.size()).subList(2, 2).add(1);"),
            "a collection over 10000 elements",
        )
        assertGuarded(start("Cursor", body = "List<Integer> l = new LinkedList<>(); ListIterator<Integer> it = l.listIterator(); while (true) { it.add(1); it.previous(); }"), "a collection over 10000 elements")
        assertGuarded(start("Builders", body = "Stream.Builder<Integer> b = Stream.builder(); while (true) b.add(1);"), "a collection over 10000 elements")
        assertGuarded(start("IntBuilder", body = "IntStream.Builder b = IntStream.builder(); while (true) b.add(1);"), "a collection over 10000 elements")
    }

    @Test
    fun `a lambda or a method reference that a JDK loop calls is metered without a loop of its own`() {
        val lambda = start("FlatMap", body = "long n = IntStream.range(0, 65_536).boxed().flatMap(i -> IntStream.range(0, 65_536).boxed()).count(); m.log(\"\" + n);", timed = true)
        assertTrue(lambda.fault?.startsWith("ForgeLimit") == true, "$lambda")
        assertTrue(lambda.tripped!!.startsWith("time budget") || lambda.tripped!!.startsWith("allocation budget"), "$lambda")
        val reference = start(
            "FlatMapReference",
            body = """
                List<Integer> l = IntStream.range(0, 10_000).boxed().toList();
                List<List<Integer>> all = new ArrayList<>();
                for (int i = 0; i < 10_000; i++) all.add(l);
                long n = 0;
                for (int i = 0; i < 1_000; i++) n += all.stream().flatMap(List::stream).count();
                m.log("" + n);
            """.trimIndent(),
            timed = true,
        )
        assertTrue(reference.fault?.startsWith("ForgeLimit") == true, "$reference")
        assertTrue(reference.tripped!!.startsWith("time budget") || reference.tripped!!.startsWith("allocation budget"), "$reference")
    }
}
