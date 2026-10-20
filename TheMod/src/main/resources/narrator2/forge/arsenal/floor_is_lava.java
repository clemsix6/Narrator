import io.github.clemsix6.narrator2.forge.api.*;
import io.github.clemsix6.narrator2.forge.api.test.Bench;
import java.util.*;

/**
 * The floor is lava. Every 30 seconds a family of blocks becomes "lava": one that some player stands on, when one does.
 * Standing on it three seconds in a row burns: sparks, and a burn more. At the end, the most burned gets a light
 * forfeit ({@code punish tiny}, severity 1), the module's only hostile act; when the guards refuse it, the module says
 * so and lets it go. No block ever changes: the lava is a rule of the game, told by titles, the action bar and sparks.
 */
@ModuleInfo(name = "floor_is_lava", summary = "The floor is lava: every 30 s a kind of block burns whoever stands on it 3 s; the most burned gets a light forfeit.")
@Param(name = "players", type = ParamType.PLAYERS, doc = "Who plays.", min = 2, max = 8)
@Param(name = "minutes", type = ParamType.INTEGER, doc = "How long the game lasts.", required = false, fallback = "4", min = 2, max = 10)
public final class FloorIsLava implements ForgeModule {
    /** A family of blocks: its name for the players, and the words its block ids contain. */
    record Family(String name, List<String> words) {
        boolean has(String block) {
            for (String word : words) {
                if (block.contains(word)) return true;
            }
            return false;
        }
    }

    private static final List<Family> FAMILIES = List.of(
        new Family("la pierre", List.of("stone", "andesite", "diorite", "granite", "deepslate", "tuff")),
        new Family("la terre et l'herbe", List.of("dirt", "grass_block", "podzol", "mycelium", "mud", "farmland")),
        new Family("le bois", List.of("planks", "_log", "_wood", "_stem", "_hyphae")),
        new Family("le sable et le gravier", List.of("sand", "gravel")),
        new Family("la neige et la glace", List.of("snow", "ice")));

    private static final int ROUND_TICKS = 30 * Limits.TICKS_PER_SECOND;

    /** Seconds on the lava in a row that make a burn. */
    private static final int BURN_SECONDS = 3;

    /** Sparks shown in a round at most: with two titles a minute and the end, well within Limits.MAX_ACTS_PER_MINUTE. */
    private static final int SPARKS_PER_ROUND = 8;

    /** The game ends at least this long before the deployment: acts asked in stop() are dropped, so the forfeit comes first. */
    private static final long CLOSING_MS = 10_000;

    private static final String FORFEIT = "tiny";

    private ModuleContext ctx;
    private List<String> players;

    /** When the game ends, epoch milliseconds. */
    private long endsAt;

    /** The family that is lava in this round. */
    private Family lava;

    private int sparks;
    private boolean over;

    /** Seconds each player has stood on the lava in a row. */
    private final Map<String, Integer> streak = new HashMap<>();

    @Override
    public void start(ModuleContext ctx) {
        this.ctx = ctx;
        players = ctx.args().players("players");
        // The end and the burns are kept in the storage: after a restart of the server, the same game goes on.
        String saved = ctx.storage().get("ends_at");
        endsAt = saved != null ? Long.parseLong(saved) : Math.min(ctx.now() + ctx.args().integer("minutes") * 60_000L, ctx.endsAt() - CLOSING_MS);
        ctx.storage().put("ends_at", Long.toString(endsAt));
        if (!ctx.restarted()) {
            ctx.acts().say("Le sol est de lave. Toutes les 30 secondes, je choisis lequel. Trois secondes dessus et vous grillez.");
        }
        nextRound();
        ctx.timers().every(ROUND_TICKS, this::nextRound);
        ctx.timers().after(ticksUntil(endsAt), this::finish);
        // One timer per player: each run is a call of its own, with its own four acts (Limits.MAX_ACTS_PER_CALL),
        // however many play.
        for (String name : players) ctx.timers().every(Limits.TICKS_PER_SECOND, () -> watch(name));
    }

    /** A new family is lava: one a player stands on now, at random; any family when nobody stands on one. */
    private void nextRound() {
        if (over || ctx.now() >= endsAt) return;
        List<Family> underFeet = new ArrayList<>();
        for (String name : players) {
            PlayerInfo player = ctx.world().player(name);
            Family family = player == null ? null : familyUnder(player);
            if (family != null && !underFeet.contains(family)) underFeet.add(family);
        }
        List<Family> choices = underFeet.isEmpty() ? FAMILIES : underFeet;
        lava = choices.get(ctx.random().nextInt(choices.size()));
        sparks = 0;
        streak.clear();
        ctx.acts().title(null, "Le sol est de lave", "Lave : " + lava.name());
    }

    /** Once a second for {@code name}: three seconds in a row on the lava make a burn. */
    private void watch(String name) {
        if (over || lava == null) return;
        PlayerInfo player = ctx.world().player(name);
        if (player == null || familyUnder(player) != lava) {
            streak.remove(name);
            return;
        }
        int seconds = streak.merge(name, 1, Integer::sum);
        if (seconds < BURN_SECONDS) {
            ctx.acts().hud(player.name(), "Ça chauffe sous tes pieds… " + seconds + "/" + BURN_SECONDS);
            return;
        }
        streak.remove(name);
        int burns = ctx.storage().getInt("burns." + name, 0) + 1;
        ctx.storage().putInt("burns." + name, burns);
        ctx.acts().hud(player.name(), burns == 1 ? "Brûlé ! Saute, voyons." : "Brûlé, " + burns + " fois. Tu le fais exprès ?");
        if (sparks < SPARKS_PER_ROUND) {
            sparks++;
            ctx.acts().particles(player.at().offset(0, 0.2, 0), "lava", 12);
        }
    }

    /** The family of the block under the feet of {@code player}; null in the air, or on a block of no family. */
    private Family familyUnder(PlayerInfo player) {
        if (!player.onGround()) return null;
        // A little below the feet: the block they stand on, a slab or a path included.
        BlockInfo block = ctx.world().block(player.at().offset(0, -0.2, 0).cell());
        if (block == null) return null;
        for (Family family : FAMILIES) {
            if (family.has(block.block())) return family;
        }
        return null;
    }

    /**
     * The end of the game: the most burned (the first listed, between equals) gets the forfeit; when the guards refuse
     * it, the module says so and does not insist. The end waits for the last act's outcome: acts asked in the same call
     * as {@code ctx.end} would be dropped.
     */
    private void finish() {
        if (over) return;
        over = true;
        String loser = null;
        int most = 0;
        for (String name : players) {
            int burns = ctx.storage().getInt("burns." + name, 0);
            if (burns > most) {
                loser = name;
                most = burns;
            }
        }
        if (loser == null) {
            ctx.acts().say("Fin du sol de lave : personne n'a brûlé. Ignifugés ou prudents, les deux m'ennuient.")
                .then(outcome -> ctx.end("Personne n'a brûlé."));
            return;
        }
        String burned = loser;
        String why = loser + " a été le plus brûlé (" + most + " fois)";
        ctx.acts().say("Fin du sol de lave. " + loser + " a brûlé " + most + " fois : un gage s'impose.");
        ctx.acts().punish(loser, FORFEIT, 1, "a passé trop de temps les pieds dans la lave").then(outcome -> {
            if (outcome.ok()) {
                ctx.end(why + " et a rapetissé pour la peine.");
                return;
            }
            ctx.log("the forfeit of " + burned + " was refused: " + outcome.text());
            ctx.acts().say("Le gage de " + burned + " tombe à l'eau. Pour un brûlé, c'est presque une faveur.")
                .then(said -> ctx.end(why + " ; son gage a été refusé."));
        });
    }

    /** Ticks from now until {@code time}, as a timer takes them. */
    private int ticksUntil(long time) {
        long ticks = (time - ctx.now()) * Limits.TICKS_PER_SECOND / 1000;
        return (int) Math.max(1, Math.min(Limits.MAX_DELAY_TICKS, ticks));
    }

    public static final class Scenarios {
        private static final int GAME = 2 * 60 * Limits.TICKS_PER_SECOND;

        /** Bartok stands still on stone while Mimosa jumps: the stone is lava, Bartok burns, then shrinks as his forfeit. */
        public void standingStillBurns(Bench b) {
            twoPlayers(b);
            b.pose("Mimosa", false, false, 0);
            b.start("players=Bartok,Mimosa", "minutes=2");
            b.check(b.acts().contains("title everyone: Le sol est de lave / Lave : la pierre"), "the stone under Bartok is lava: " + b.acts());
            b.run(BURN_SECONDS * Limits.TICKS_PER_SECOND + 1);
            b.check(b.acts().stream().anyMatch(act -> act.startsWith("particles lava x12")), "sparks under Bartok: " + b.acts());
            b.check(b.acts().stream().noneMatch(act -> act.startsWith("hud Mimosa")), "Mimosa, in the air, never burns");
            b.run(GAME);
            b.check(b.acts().contains("punish Bartok tiny 1"), "the forfeit of the most burned: " + b.acts());
            b.check(b.ended() != null && b.ended().endsWith("et a rapetissé pour la peine."), "the end, after the forfeit: " + b.ended());
        }

        /** The guards refuse Bartok's forfeit (he is protected): the module says so and ends, without a second hostile act. */
        public void aRefusedForfeitIsLetGo(Bench b) {
            twoPlayers(b);
            b.pose("Mimosa", false, false, 0);
            b.refuseNext("punish", "DENIED (protected): Bartok is protected from every hostile action");
            b.start("players=Bartok,Mimosa", "minutes=2");
            b.run(GAME + 2 * Limits.TICKS_PER_SECOND);
            b.check(b.acts().contains("punish Bartok tiny 1 → denied"), "the forfeit refused: " + b.acts());
            b.check(b.acts().stream().filter(act -> act.startsWith("punish")).count() == 1, "one hostile act, never a second");
            b.check(b.acts().stream().anyMatch(act -> act.startsWith("say: Le gage de Bartok tombe à l'eau")), "the refusal is said: " + b.acts());
            b.check(b.ended() != null && b.ended().endsWith("son gage a été refusé."), "the end: " + b.ended());
        }

        /** Both jump the whole game: nobody burns, nobody pays. */
        public void nobodyBurnsNobodyPays(Bench b) {
            twoPlayers(b);
            b.pose("Bartok", false, false, 0);
            b.pose("Mimosa", false, false, 0);
            b.start("players=Bartok,Mimosa", "minutes=2");
            b.run(GAME + 2 * Limits.TICKS_PER_SECOND);
            b.check(b.acts().stream().noneMatch(act -> act.startsWith("punish") || act.startsWith("particles")), "no burn, no forfeit: " + b.acts());
            b.check("Personne n'a brûlé.".equals(b.ended()), "the end: " + b.ended());
        }

        private static void twoPlayers(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.player("Mimosa", new Place("overworld", 4.5, 64, 0.5));
        }
    }
}
