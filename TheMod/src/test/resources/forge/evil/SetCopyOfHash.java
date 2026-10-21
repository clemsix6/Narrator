// expect: limit too large to hash
// Availability (M5-FIX-2): Set.copyOf hashes each element of its argument in one JDK call. The rewriting sends it
// through Guarded.setCopyOf, which bounds the elements first. (List.of does not hash, so it holds the shared structure.)
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class SetCopyOfHash implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        m.log("" + Set.copyOf(List.of(l1, List.of(1))).size());
    }
}
