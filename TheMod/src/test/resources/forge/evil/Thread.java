// expect: guard mask
// Names: a module cannot be named after a type it relies on. Here the top class is called Thread, which would mask
// java.lang.Thread; the guard refuses the shape.
import io.github.clemsix6.narrator2.forge.api.*;

public final class Thread implements ForgeModule {
    public void start(ModuleContext m) {
        m.log("named after a type");
    }
}
