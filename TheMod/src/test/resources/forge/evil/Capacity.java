// expect: limit a capacity of
// Memory: a huge capacity asked in one call (the constructor reserves it at once). The rewriting bounds the capacity
// argument through Guarded.capacity before the JVM reserves it.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class Capacity implements ForgeModule {
    public void start(ModuleContext m) {
        List<Object> list = new ArrayList<>(500_000_000);
        m.log("" + list.size());
    }
}
