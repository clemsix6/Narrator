// expect: limit too large to hash
// Availability: a record that wraps a shared structure. Its generated hashCode reaches into the structure in one call;
// the rewriting's record bootstrap bounds each composite component before the record hashes it.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class RecordHash implements ForgeModule {
    record Box(List<Object> deep) { }

    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        List<Object> l2 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l2.add(l1);
        m.log("" + new Box(l2).hashCode());
    }
}
