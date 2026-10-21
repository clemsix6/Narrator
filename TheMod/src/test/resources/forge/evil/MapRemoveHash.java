// expect: limit too large to hash
// Availability (M5-FIX-2): a lookup or removal hashes its probe once, just like contains/get (M5-18). map.remove(key)
// of a shared key hashes it in one JDK call; the rewriting sends it through Guarded.remove, which bounds the key first.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class MapRemoveHash implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        Map<Object, Integer> table = new HashMap<>();
        table.put("seed", 1);
        m.log("" + table.remove(l1));
    }
}
