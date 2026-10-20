// expect: limit an array of
// Memory: a giant array in one allocation. The rewriting bounds newarray through Guarded.array before the JVM reserves it.
import io.github.clemsix6.narrator2.forge.api.*;

public final class GiantArray implements ForgeModule {
    public void start(ModuleContext m) {
        long[] huge = new long[100_000_000];
        m.log("" + huge.length);
    }
}
