// expect: limit too large to hash
// Availability: Arrays.deepHashCode descends into nested arrays, so a shared array of arrays hashes 10^8 slots in one
// call. The rewriting sends it through Guarded.deepHashCode, which bounds the walk.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class ArraysDeepHash implements ForgeModule {
    public void start(ModuleContext m) {
        Object[] inner = new Object[10_000];
        Object[] shared = new Object[10_000];
        for (int i = 0; i < 10_000; i++) shared[i] = inner;
        Object[] outer = new Object[10_000];
        for (int i = 0; i < 10_000; i++) outer[i] = shared;
        m.log("" + Arrays.deepHashCode(outer));
    }
}
