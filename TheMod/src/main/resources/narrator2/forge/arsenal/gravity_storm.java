import io.github.clemsix6.narrator2.forge.api.*;
import io.github.clemsix6.narrator2.forge.api.test.Bench;
import java.util.*;

/**
 * A gravity storm. In waves, 40 seconds apart, one player at random floats for a minute ({@code punish moon_gravity},
 * severity 1), those not lifted yet first. Any act may be refused: when the guards spare a player (protected, already
 * punished, lifted too lately, just dead…), the storm tries the next one; when they spare everyone, the wave passes
 * and the Director is told. After the last wave, the module ends itself.
 */
@ModuleInfo(name = "gravity_storm", summary = "Gravity storm: in waves 40 s apart, a random player floats for a minute.")
@Param(name = "players", type = ParamType.PLAYERS, doc = "Whom the storm may lift.", min = 1, max = 8)
@Param(name = "waves", type = ParamType.INTEGER, doc = "How many waves, 40 seconds apart.", required = false, fallback = "3", min = 2, max = 6)
public final class GravityStorm implements ForgeModule {
    private static final int FIRST_WAVE = 10 * Limits.TICKS_PER_SECOND;
    private static final int WAVE_GAP = 40 * Limits.TICKS_PER_SECOND;
    private static final String REASON = "a été emporté par la tempête de gravité du Narrateur";

    private static final List<String> QUIPS = List.of(
        "%s s'envole. La gravité reviendra le chercher, elle a son adresse.",
        "%s pèse soudain moins lourd que ses excuses.",
        "La tempête emporte %s. Inutile de lui faire coucou, il ne redescendra pas plus vite.",
        "%s découvre la gravité lunaire. Sans fusée, et sans dignité.");

    private ModuleContext ctx;
    private List<String> players;
    private int waves;

    /** The waves blown so far, kept in the storage: after a restart of the server, the storm goes on where it was. */
    private int blown;

    private final List<String> lifted = new ArrayList<>();

    @Override
    public void start(ModuleContext ctx) {
        this.ctx = ctx;
        players = ctx.args().players("players");
        waves = ctx.args().integer("waves");
        blown = ctx.storage().getInt("waves_blown", 0);
        if (!ctx.restarted()) ctx.acts().say("Une tempête de gravité se lève. Tenez-vous à quelque chose de lourd.");
        if (blown < waves) {
            ctx.timers().after(FIRST_WAVE, this::nextWave);
        } else {
            finish();
        }
    }

    /** A wave: the players online, at random, those not lifted yet first; the first one the guards let through floats. */
    private void nextWave() {
        int wave = ++blown;
        ctx.storage().putInt("waves_blown", wave);
        List<String> candidates = new ArrayList<>();
        for (String name : players) {
            if (ctx.world().player(name) != null) candidates.add(name);
        }
        Collections.shuffle(candidates, ctx.random());
        candidates.sort(Comparator.comparing(lifted::contains));
        lift(wave, candidates, 0);
        if (wave < waves) ctx.timers().after(WAVE_GAP, this::nextWave);
    }

    /**
     * Lifts {@code candidates[index]}. The outcome comes in a call of its own, a new act of the engine: when the guards
     * refused that player, it tries the next one, never the same one again; nobody left, the wave passes.
     */
    private void lift(int wave, List<String> candidates, int index) {
        if (index == candidates.size()) {
            passed(wave, candidates);
            return;
        }
        String target = candidates.get(index);
        ctx.acts().punish(target, "moon_gravity", 1, REASON).then(outcome -> {
            if (outcome.ok()) {
                floated(wave, target);
            } else {
                ctx.log("wave " + wave + ": " + target + " spared, " + outcome.text());
                lift(wave, candidates, index + 1);
            }
        });
    }

    private void floated(int wave, String target) {
        if (!lifted.contains(target)) lifted.add(target);
        Pending said = ctx.acts().say(QUIPS.get(ctx.random().nextInt(QUIPS.size())).formatted(target));
        // After the last wave, the end waits for the line: an act asked in the same call as ctx.end would be dropped.
        if (wave == waves) said.then(outcome -> finish());
    }

    /** Nobody floated in this wave: the guards spared every candidate, or nobody was online. */
    private void passed(int wave, List<String> spared) {
        if (wave == waves) {
            finish();
        } else if (spared.isEmpty()) {
            ctx.tellDirector("La vague " + wave + " de la tempête de gravité n'a trouvé personne en ligne.");
        } else {
            ctx.tellDirector("La vague " + wave + " de la tempête de gravité n'a soulevé personne : les garde-fous ont épargné "
                + String.join(", ", spared) + ".");
        }
    }

    private void finish() {
        if (lifted.isEmpty()) {
            ctx.end("La tempête de gravité est passée sans soulever personne.");
        } else {
            ctx.end("La tempête de gravité est passée : " + String.join(", ", lifted) + (lifted.size() == 1 ? " a flotté." : " ont flotté."));
        }
    }

    public static final class Scenarios {
        /** Two players, two waves: each floats once, and the storm ends itself. */
        public void eachWaveLiftsSomeone(Bench b) {
            twoPlayers(b);
            b.start("players=Bartok,Mimosa", "waves=2");
            b.run(FIRST_WAVE + WAVE_GAP + 2 * Limits.TICKS_PER_SECOND);
            List<String> lifted = punished(b, "");
            b.check(lifted.size() == 2 && !lifted.get(0).equals(lifted.get(1)), "each wave lifts another player: " + b.acts());
            b.check(b.ended() != null && b.ended().contains("Bartok") && b.ended().contains("Mimosa"), "the end: " + b.ended());
        }

        /** The guards refuse the first player the wave picks: the storm lifts the other one in the same wave. */
        public void aRefusedPlayerIsSkipped(Bench b) {
            twoPlayers(b);
            b.refuseNext("punish", "DENIED (already_punished): a punishment of another act still runs against them");
            b.start("players=Bartok,Mimosa", "waves=2");
            b.run(FIRST_WAVE + 2 * Limits.TICKS_PER_SECOND);
            List<String> refused = punished(b, " → denied");
            List<String> lifted = punished(b, "");
            b.check(refused.size() == 1 && lifted.size() == 1 && !refused.get(0).equals(lifted.get(0)), "the other one floats: " + b.acts());
            b.check(b.notes().stream().noneMatch(note -> note.startsWith("director:")), "nothing to tell the Director: " + b.notes());
        }

        /** Bartok alone, spared at the first wave: the wave passes, the Director is told, and the next wave lifts him. */
        public void aWaveWithNobodyToLiftWaits(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.refuseNext("punish", "DENIED (protected): Bartok is protected from every hostile action");
            b.start("players=Bartok", "waves=2");
            b.run(FIRST_WAVE + 2 * Limits.TICKS_PER_SECOND);
            b.check(punished(b, "").isEmpty(), "nobody floats at the first wave: " + b.acts());
            b.check(b.notes().stream().anyMatch(note -> note.startsWith("director: La vague 1")), "the Director is told: " + b.notes());
            b.run(WAVE_GAP);
            b.check(punished(b, "").equals(List.of("Bartok")), "the next wave lifts him: " + b.acts());
            b.check("La tempête de gravité est passée : Bartok a flotté.".equals(b.ended()), "the end: " + b.ended());
        }

        /** The players of the acts "punish <name> moon_gravity 1" followed by {@code status} ("" when done). */
        private static List<String> punished(Bench b, String status) {
            String tail = " moon_gravity 1" + status;
            List<String> names = new ArrayList<>();
            for (String act : b.acts()) {
                if (act.startsWith("punish ") && act.endsWith(tail)) names.add(act.substring("punish ".length(), act.length() - tail.length()));
            }
            return names;
        }

        private static void twoPlayers(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.player("Mimosa", new Place("overworld", 4.5, 64, 0.5));
        }
    }
}
