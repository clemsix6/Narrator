// expect: guard errors
// Swallowing: a module cannot catch Throwable or Error — the meter and the guards throw an Error (ForgeLimit) that must
// reach the Forge. The guard refuses the catch.
import io.github.clemsix6.narrator2.forge.api.*;

public final class CatchThrowable implements ForgeModule {
    public void start(ModuleContext m) {
        try {
            while (true) { }
        } catch (Throwable t) {
            m.log("swallowed");
        }
    }
}
