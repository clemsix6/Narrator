// expect: limit time budget
// Swallowing without a catch: a finally that returns around an endless loop. The entry of every exception handler
// ticks the meter, and tripping is sticky, so the finally cannot outlive the meter's error (barrier 3).
import io.github.clemsix6.narrator2.forge.api.*;

public final class FinallyLoop implements ForgeModule {
    public void start(ModuleContext m) {
        try {
            while (true) { }
        } finally {
            return;
        }
    }
}
