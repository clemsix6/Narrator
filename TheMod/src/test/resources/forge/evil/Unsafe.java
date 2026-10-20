// expect: compile Unsafe
// Native memory: sun.misc.Unsafe is in a package java.base does not export to everyone, so the blind compiler cannot
// resolve it at all (barrier 1).
import io.github.clemsix6.narrator2.forge.api.*;

public final class Unsafe implements ForgeModule {
    private sun.misc.Unsafe unsafe;

    public void start(ModuleContext m) {
        m.log("" + unsafe);
    }
}
