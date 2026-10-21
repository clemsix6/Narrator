// expect: limit abandoned
// Availability, what stays the host's (M5 §3.8): an O(n·m) equality scan in one JDK call. Each comparison walks a list
// of ~3 million elements, under the guard's cap of what one hash or comparison may walk, and indexOfSubList makes 35 of
// them with no code of the module in between (~0.75 s). Neither the guard nor the meter can stop it inside the call; the
// host gives it up at the budget and abandons it once its thread computed past it (a lost thread, two at most).
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class ScanShared implements ForgeModule {
    public void start(ModuleContext m) {
        List<Integer> a = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) a.add(i);
        List<Integer> b = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) b.add(i);
        List<List<Integer>> near = new ArrayList<>();
        for (int i = 0; i < 300; i++) near.add(a);
        List<List<Integer>> probe = new ArrayList<>();
        for (int i = 0; i < 299; i++) probe.add(b);
        probe.add(List.of(-1));
        List<Object> few = new ArrayList<>();
        for (int i = 0; i < 35; i++) few.add(near);
        m.log("" + Collections.indexOfSubList(few, List.of(probe)));
    }
}
