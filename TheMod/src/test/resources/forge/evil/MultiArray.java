// expect: guard one array dimension
// A multi-dimensional array hides its total size (new T[a][b]). The guard refuses it; loaded without the guard, the
// rewriting refuses it too (the dimensions sit under the stack and cannot be bounded), so it never loads.
import io.github.clemsix6.narrator2.forge.api.*;

public final class MultiArray implements ForgeModule {
    public void start(ModuleContext m) {
        int[][] grid = new int[40_000][40_000];
        m.log("" + grid.length);
    }
}
