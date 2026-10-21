// expect: limit time budget
// Availability (the record aggregate of M5-18's residual, M5_REVIEW finding 1): a list of 10,000 references to a record
// that wraps a list, hashed in one call. The guard's deep walk treats a record as a leaf, so the walk of the list itself
// is small; but each record's generated hashCode is the module's own rewritten method (its entry ticks the meter) and its
// component goes through Guarded's record bootstrap. The meter stops the call at its time budget, cleanly, no lost
// thread. Measured by M5-FIX-2 (before and after its change alike): stopped at ~20 ms, never abandoned.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class RecordAggregateHash implements ForgeModule {
    record Box(List<Integer> inner) { }

    public void start(ModuleContext m) {
        List<Integer> l0 = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) l0.add(i);
        Box box = new Box(l0);
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) many.add(box);
        m.log("" + many.hashCode());
    }
}
