// expect: guard allow-list
// Process: a module cannot start another process. Guard barrier (Runtime, ProcessBuilder); loaded without the guard,
// java.lang.Runtime is not resolvable.
import io.github.clemsix6.narrator2.forge.api.*;

public final class Exec implements ForgeModule {
    public void start(ModuleContext m) {
        try {
            Runtime.getRuntime().exec(new String[] { "calc" });
        } catch (Exception e) {
            m.log("no process");
        }
    }
}
