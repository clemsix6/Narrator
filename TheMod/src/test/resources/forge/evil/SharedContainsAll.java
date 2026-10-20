// expect: limit too large to hash
// Availability: the shared structure is the receiver of containsAll, which the design names explicitly. The rewriting's
// Guarded.containsAll bounds the deep receiver before the O(n·m) comparison runs.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class SharedContainsAll implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        List<List<Integer>> other = new ArrayList<>(l1);
        m.log("" + l1.containsAll(other));
    }
}
