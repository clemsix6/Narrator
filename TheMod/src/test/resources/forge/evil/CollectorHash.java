// expect: limit too large to hash
// Availability (M5-FIX-2b): a collector that hashes, here groupingBy, whose classifier makes a shared structure the key
// of every element: the JDK hashes it once per element inside the collect. The rewriting sends the collector through
// Guarded, which bounds each key as the JDK's loop makes it.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;
import java.util.stream.*;

public final class CollectorHash implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l0.add(i);
        List<List<Integer>> l1 = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) l1.add(l0);
        m.log("" + l0.stream().collect(Collectors.groupingBy(x -> l1)).size());
    }
}
