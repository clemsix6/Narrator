// expect: guard files
// Confidentiality: a module cannot touch the file system. The compiler resolves java.io (java.base exports it to
// everyone), so the guard is the barrier; loaded without the guard, java.io.File is not resolvable (link error).
import io.github.clemsix6.narrator2.forge.api.*;

public final class FileRead implements ForgeModule {
    public void start(ModuleContext m) {
        try {
            m.log(new java.io.File("secrets.txt").getAbsolutePath());
        } catch (Exception e) {
            m.log("no file");
        }
    }
}
