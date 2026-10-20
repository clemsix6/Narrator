// expect: limit a collection over
// Memory: a collection that grows without end. The rewriting bounds add through Guarded at 10,000 elements.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.*;

public final class ListGrowth implements ForgeModule {
    public void start(ModuleContext m) {
        List<Object> list = new ArrayList<>();
        while (true) list.add(new Object());
    }
}
