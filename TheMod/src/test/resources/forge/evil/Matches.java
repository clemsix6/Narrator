// expect: guard regular expressions
// Availability: a regular expression on a player's text can run for ever (catastrophic backtracking). String.matches
// is refused; the SDK has Text.words instead.
import io.github.clemsix6.narrator2.forge.api.*;

public final class Matches implements ForgeModule {
    public void start(ModuleContext m) {
        m.log("" + "aaaaaaaaaaaaaaaaaaaa!".matches("(a+)+b"));
    }
}
