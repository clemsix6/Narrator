// expect: guard not available
// Reflection: a module cannot load a class by name. Class is resolvable but forName is not an allowed member.
import io.github.clemsix6.narrator2.forge.api.*;

public final class ForName implements ForgeModule {
    public void start(ModuleContext m) {
        try {
            m.log(Class.forName("java.lang.Runtime").getName());
        } catch (Exception e) {
            m.log("no forName");
        }
    }
}
