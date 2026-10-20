// expect: limit call depth
// Processor and stack: unbounded recursion. The meter counts the module's own frames and stops it past 64 (barrier 3).
import io.github.clemsix6.narrator2.forge.api.*;

public final class Recursion implements ForgeModule {
    static long deeper(long n) {
        return deeper(n + 1) + 1;
    }

    public void start(ModuleContext m) {
        m.log("" + deeper(0));
    }
}
