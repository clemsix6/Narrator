// expect: limit too large to hash
// Availability (M5-FIX-2): a copy constructor of a hash table hashes every element again in one JDK call, so a shared
// structure fed to new HashSet<>(coll) hashes 10^8 slots uninterruptibly. The rewriting checks the collection through
// Guarded.hashedElements before the constructor, bounding the walk before the JDK does it.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class CopyCtorHash implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        m.log("" + new HashSet<>(l1).size());
    }
}
