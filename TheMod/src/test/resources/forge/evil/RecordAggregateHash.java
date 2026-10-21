// expect: limit time budget
// Availability (M5-18's residual, closed by M5-FIX-2): a list of 10,000 records, each wrapping a shared list, hashed in
// one call. The guard's deep walk treats a record as a leaf (it does not call the module's own hashCode), so the walk
// of the list itself is small; but each record's generated hashCode goes through Guarded's record bootstrap, whose
// per-component deep walk ends on a meter checkpoint (Guarded.deep). Their sum trips the meter's time budget well before
// the host would abandon the call: a clean stop, no lost thread.
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
