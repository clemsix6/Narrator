// expect: limit too large to hash
// Availability (M5-FIX-2): the immutable factory Set.of hashes each element in one JDK call. The rewriting sends it
// through Guarded.setOf, which bounds each element before the JDK hashes it.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class SetOfHash implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        m.log("" + Set.of(l1, "a", "b").size());
    }
}
