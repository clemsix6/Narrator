// expect: limit abandoned
// Availability, the documented residual (M5-18): Stream.distinct() hashes its elements through a HashSet the rewriting
// does not wrap, so a shared structure fed to it still hashes in one uninterruptible JDK call. The guard does not catch
// this, but the host does: the server thread waits at most the budget, abandons the call, and the module is disabled
// (a lost thread, bounded to two for the whole Forge). A smaller structure here, so the lost thread ends quickly.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;
import java.util.stream.*;

public final class DistinctShared implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        m.log("" + Stream.of(l1).distinct().count());
    }
}
