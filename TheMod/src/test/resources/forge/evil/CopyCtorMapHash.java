// expect: limit too large to hash
// Availability (M5-FIX-2): new HashMap<>(map) re-hashes every key in one JDK call. A singletonMap holds the shared key
// without hashing it (no table), so the copy is the first hash; the rewriting checks the keys through Guarded.hashedKeys
// before the constructor.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class CopyCtorMapHash implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        m.log("" + new HashMap<>(Collections.singletonMap(l1, 1)).size());
    }
}
