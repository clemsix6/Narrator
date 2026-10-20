// expect: limit too large to hash
// Availability, the implicit path: the shared structure is not hashed explicitly but used as a key of a HashMap, which
// hashes it inside put. The rewriting's Guarded.put bounds a composite key before the JDK hashes it.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class SharedKey implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        Map<Object, Integer> table = new HashMap<>();
        table.put(l1, 1);
        m.log("" + table.size());
    }
}
