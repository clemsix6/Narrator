// expect: guard network
// Network: a module cannot open a connection. Guard barrier; loaded without the guard, java.net is not resolvable.
import io.github.clemsix6.narrator2.forge.api.*;

public final class Socket implements ForgeModule {
    public void start(ModuleContext m) {
        try {
            new java.net.Socket("example.com", 80).close();
        } catch (Exception e) {
            m.log("no socket");
        }
    }
}
