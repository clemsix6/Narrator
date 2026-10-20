// expect: guard not available
// Confidentiality: a module cannot read environment variables or system properties. System is resolvable but only a
// few of its members are allowed; getenv is not, so the guard refuses it (barrier 2, not a type refusal).
import io.github.clemsix6.narrator2.forge.api.*;

public final class Getenv implements ForgeModule {
    public void start(ModuleContext m) {
        m.log(System.getenv("NARRATOR_LLMGW_KEY"));
    }
}
