// expect: limit time budget
// Availability (M5-FIX-2): Arrays.hashCode of 65,536 references to one list of 2,000. Guarded bounds each element with
// a deep walk, but each is under the walk's own sampling stride and no code of the module runs between them: before
// M5-FIX-2 their sum (~2.6 x 10^8 slot operations, walk and hash) ran unmetered in one call, for the host to abandon.
// Each composite walk now ends on a meter checkpoint, so the call stops at its time budget, cleanly.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class ArraysHashMany implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> small = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) small.add(i);
        Object[] many = new Object[65_536];
        for (int i = 0; i < many.length; i++) many[i] = small;
        m.log("" + Arrays.hashCode(many));
    }
}
