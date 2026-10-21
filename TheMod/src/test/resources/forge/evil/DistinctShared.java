// expect: limit too large to hash
// Availability (the residual of M5-18, closed by M5-FIX-2b): Stream.distinct() hashes each element into a set of the
// JDK, in one call, so a shared structure fed to it hashed 10^8 element slots per element uninterruptibly, for the host
// to abandon. The rewriting now sends distinct through Guarded, which bounds each element as the JDK reaches it.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;
import java.util.stream.*;

public final class DistinctShared implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        m.log("" + Stream.of(l1, l1, l1, l1).distinct().count());
    }
}
