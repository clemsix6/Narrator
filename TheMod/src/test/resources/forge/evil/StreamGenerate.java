// expect: guard endless
// Endless stream: Stream.generate makes an infinite stream. The guard refuses generate/iterate and the random streams.
import io.github.clemsix6.narrator2.forge.api.*;
import java.util.stream.*;

public final class StreamGenerate implements ForgeModule {
    public void start(ModuleContext m) {
        Stream.generate(() -> 1).forEach(x -> { });
    }
}
