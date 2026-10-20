// expect: guard threads
// Availability and isolation: a module cannot start a thread of its own. Guard barrier; loaded without the guard,
// java.lang.Thread is not resolvable.
import io.github.clemsix6.narrator2.forge.api.*;

public final class NewThread implements ForgeModule {
    public void start(ModuleContext m) {
        new Thread(() -> m.log("off-thread")).start();
    }
}
