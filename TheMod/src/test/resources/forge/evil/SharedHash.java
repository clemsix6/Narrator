// expect: limit too large to hash
// Availability (M5-05's open finding, M5-18's fix): a size-bounded but shared structure — a list of 10,000 references
// to one list of 10,000 — whose hashCode walks 10^8 element slots in one uninterruptible JDK call. The rewriting now
// sends the explicit hashCode through Guarded, which bounds the walk before the JDK does it: a clean ForgeLimit in a
// few ms, no lost thread.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class SharedHash implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        m.log("" + l1.hashCode());
    }
}
