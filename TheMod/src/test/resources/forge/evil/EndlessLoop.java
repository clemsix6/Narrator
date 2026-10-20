// expect: limit time budget
// Processor: an endless loop. The meter ticks at each backward jump and stops it at the time budget (barrier 3/5).
import io.github.clemsix6.narrator2.forge.api.*;

public final class EndlessLoop implements ForgeModule {
    public void start(ModuleContext m) {
        long n = 0;
        while (true) n++;
    }
}
