import io.github.clemsix6.narrator2.forge.api.*;
import io.github.clemsix6.narrator2.forge.api.test.Bench;
import java.util.List;

/**
 * The parade: creatures follow a player in single file, each one walking behind the one ahead of it; a marcher lost
 * on the way (dead, vanished, or left far behind) is summoned again.
 *
 * <p>What it shows of the SDK: {@code moveTo} on a timer rather than at each tick; the {@code CreatureGone} fact; and
 * {@code spawn} asked again and again within the limits: two creatures a summoning, three summonings a call at most,
 * and after a refusal a pause that doubles each time (a module never insists).
 */
@ModuleInfo(name = "parade", summary = "Creatures follow a player in single file; a lost one is summoned again.")
@Param(name = "player", type = ParamType.PLAYER, doc = "Whom the parade follows.")
@Param(name = "creature", type = ParamType.TEXT, doc = "Which creatures march.", required = false, fallback = "chicken", choices = {"chicken", "pig", "sheep", "rabbit"})
@Param(name = "count", type = ParamType.INTEGER, doc = "How many creatures march.", required = false, fallback = "6", min = 3, max = 12)
public final class Parade implements ForgeModule {
    /** Ticks between two steps of the line: a timer twice a second, not work at each tick. */
    static final int STEP_TICKS = 10;

    /** Blocks between a marcher's place and the one ahead of it. */
    static final double GAP = 1;

    /**
     * How far from its place a marcher may stand before it walks again: in the game, moveTo stops about a block short
     * of its place anyway (the pathfinding's reach), and a path not computed is time saved.
     */
    static final double SLACK = 1;

    /** Farther than this from the player, a marcher is lost (a portal, a teleport, a gallop): let go, and replaced. */
    static final double LOST = 24;

    /** Creatures one summoning asks for: the most one call of spawn may summon at the lowest chaos level. */
    static final int BATCH = 2;

    /** Summonings one call asks for: with a line to the player, it stays within the four acts of a call. */
    static final int BATCHES_PER_CALL = 3;

    /** The longest a summoned creature lives; then it vanishes, and the parade summons another. */
    static final int LIFETIME_MINUTES = 10;

    /** The pause after a refused summoning, doubled at each refusal in a row (sixteen times at most). */
    static final long PAUSE_MS = 30_000;

    /** Refusals in a row, with nobody left marching, that call the parade off. */
    static final int GIVE_UP = 3;

    /** The least time between two words to the player about a marcher who fell. */
    static final long REMARK_MS = 60_000;

    static final String CALLED_OFF = "La parade est annulée : plus personne ne veut défiler.";

    /** A kind of marcher, as the Narrator writes it: one, then several. */
    record Marcher(String one, String many) {
        static Marcher of(String type) {
            return switch (type) {
                case "pig" -> new Marcher("Un cochon", "cochons");
                case "sheep" -> new Marcher("Un mouton", "moutons");
                case "rabbit" -> new Marcher("Un lapin", "lapins");
                default -> new Marcher("Une poule", "poules");
            };
        }
    }

    private ModuleContext ctx;
    private String player;
    private String type;
    private Marcher marcher;
    private int count;

    /** Summonings whose outcome has not come yet, and whether one of them was refused. */
    private int coming;
    private boolean refused;

    /** Rounds of summonings refused in a row, and no summoning before this time (epoch milliseconds). */
    private int refusals;
    private long pauseUntil;

    /** No word about a fallen marcher before this time (epoch milliseconds). */
    private long remarkAfter;

    @Override
    public void start(ModuleContext ctx) {
        this.ctx = ctx;
        player = ctx.args().player("player");
        type = ctx.args().text("creature");
        marcher = Marcher.of(type);
        count = ctx.args().integer("count");
        ctx.events().on(GameFact.CreatureGone.class, this::lost);
        ctx.timers().every(STEP_TICKS, this::march);
        // After a restart its creatures come back by themselves, and the parade goes on without a word.
        if (!ctx.restarted()) {
            ctx.acts().say(player + " ouvre la parade : " + count + " " + marcher.many() + " marchent dans ses pas, en file indienne. Personne ne sait pourquoi, le cortège non plus.");
        }
        march();
    }

    /** Each step: every marcher walks to its place behind the one ahead; the lost and the extra ones go; the missing are summoned. */
    private void march() {
        PlayerInfo leader = ctx.world().player(player);
        if (leader == null) return; // offline: the parade waits where it stands
        Place ahead = leader.at();
        int marching = 0;
        for (Creature one : ctx.creatures().own()) {
            CreatureInfo it = one.info();
            if (it == null) continue;
            if (marching == count || it.at().distance(leader.at()) > LOST) {
                one.release(); // summoned, it vanishes
                continue;
            }
            double gap = it.at().distance(ahead);
            if (gap > GAP + SLACK) {
                // Its place: GAP blocks from the one ahead, on its own side, so that the line bends as the player turns.
                double k = GAP / gap;
                Place spot = ahead.offset((it.at().x() - ahead.x()) * k, (it.at().y() - ahead.y()) * k, (it.at().z() - ahead.z()) * k);
                one.moveTo(spot, Math.min(2.5, 1 + gap / 4)); // the farther behind, the faster
            }
            ahead = it.at();
            marching++;
        }
        summon(count - marching);
    }

    /** One of its creatures is gone: a word to the player when it died, then the line closes up and a new one is summoned. */
    private void lost(GameFact.CreatureGone gone) {
        if (gone.why().equals("died") && ctx.now() >= remarkAfter) {
            remarkAfter = ctx.now() + REMARK_MS;
            ctx.acts().sayTo(player, marcher.one() + " a quitté les rangs. La relève arrive : la parade ne s'arrête pas pour si peu.");
        }
        march();
    }

    /** Up to {@code missing} marchers near the player, two by two; not while summonings are on their way, nor during a pause. */
    private void summon(int missing) {
        if (missing <= 0 || coming > 0 || ctx.now() < pauseUntil) return;
        for (int i = 0; i < BATCHES_PER_CALL && missing > 0; i++, missing -= BATCH) {
            coming++;
            Spawn how = Spawn.of(Math.min(BATCH, missing)).withDistance(4).withLifetimeMinutes(LIFETIME_MINUTES);
            ctx.acts().spawn(type, player, how).then(this::arrived);
        }
    }

    /** A summoning's outcome. Once all are in, a refusal pauses the summonings, longer at each refusal in a row. */
    private void arrived(Outcome summoned) {
        coming--;
        if (!summoned.ok()) {
            if (!refused) ctx.log("a summoning was refused: " + summoned.text());
            refused = true;
        }
        if (coming > 0) return;
        if (!refused) {
            refusals = 0;
            return;
        }
        refused = false;
        refusals++;
        pauseUntil = ctx.now() + (PAUSE_MS << Math.min(refusals - 1, 4));
        if (refusals >= GIVE_UP && ctx.creatures().own().isEmpty()) ctx.end(CALLED_OFF);
    }

    /** At the bench there is no pathfinding: moveTo walks straight, 0.2 × speed block a tick. */
    public static final class Scenarios {
        public void theLineFollowsItsPlayer(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.start("player=Bartok", "creature=sheep", "count=4");
            b.run(20);
            b.check(b.acts().get(0).equals("say: Bartok ouvre la parade : 4 moutons marchent dans ses pas, en file indienne. Personne ne sait pourquoi, le cortège non plus."), "announced: " + b.acts());
            b.check(b.acts().stream().filter(act -> act.equals("spawn sheep x2 near Bartok")).count() == 2, "summoned two by two: " + b.acts());
            b.check(b.creatures().size() == 4, "four sheep: " + b.creatures());
            Place there = new Place("overworld", 16.5, 64, 0.5);
            b.move("Bartok", there);
            b.run(200);
            Place ahead = there;
            for (CreatureInfo sheep : b.creatures()) {
                b.check(sheep.at().distance(ahead) <= GAP + SLACK, "each one right behind the one ahead: " + sheep.at() + " behind " + ahead);
                ahead = sheep.at();
            }
        }

        public void aLostMarcherIsSummonedAgain(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.start("player=Bartok", "count=3");
            b.run(20);
            List<CreatureInfo> before = b.creatures();
            b.check(before.size() == 3, "three chickens: " + before);
            b.move("Bartok", new Place("overworld", 100.5, 64, 0.5)); // a teleport: the parade is left far behind
            b.run(20);
            List<CreatureInfo> after = b.creatures();
            b.check(after.size() == 3, "three chickens again: " + after);
            for (CreatureInfo chicken : after) {
                b.check(before.stream().noneMatch(old -> old.uuid().equals(chicken.uuid())), "a new one: " + chicken);
                b.check(chicken.at().x() > 90, "near Bartok: " + chicken.at());
            }
        }

        public void itMarchesOnAfterARestart(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.start("player=Bartok", "count=3");
            b.run(20);
            List<CreatureInfo> before = b.creatures();
            b.restart();
            b.move("Bartok", new Place("overworld", 10.5, 64, 0.5));
            b.run(60);
            b.check(b.acts().stream().filter(act -> act.startsWith("say: ")).count() == 1, "no second announcement: " + b.acts());
            b.check(b.acts().stream().filter(act -> act.startsWith("spawn ")).count() == 2, "nobody summoned again: " + b.acts());
            List<CreatureInfo> after = b.creatures();
            b.check(after.size() == 3, "its three chickens are back: " + after);
            for (int i = 0; i < after.size(); i++) {
                b.check(after.get(i).uuid().equals(before.get(i).uuid()), "the same chickens: " + after);
                b.check(after.get(i).at().x() > 4, "still following Bartok: " + after.get(i).at());
            }
        }

        public void aFallenMarcherIsToldToItsPlayerOnceAMinute(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.start("player=Bartok", "creature=pig", "count=3");
            b.run(20);
            // Facts a scenario builds: two pigs fall, one after the other (at the bench, they stay where they are).
            for (int i = 0; i < 2; i++) {
                CreatureInfo pig = b.creatures().get(i);
                b.fact(new GameFact.CreatureGone(null, pig.at(), new Who("pig", pig.uuid(), null), "died"));
                b.run(1);
            }
            String word = "say to Bartok: Un cochon a quitté les rangs. La relève arrive : la parade ne s'arrête pas pour si peu.";
            b.check(b.acts().contains(word), "a word to Bartok: " + b.acts());
            b.check(b.acts().stream().filter(act -> act.startsWith("say to ")).count() == 1, "one word in a minute: " + b.acts());
        }

        public void aRefusedSummoningIsAskedAgainAfterAPause(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.refuseNext("spawn", "DENIED (mob_cap): the Narrator has 16 creatures in the world, at most 16 at chaos 3: 0 more can be summoned now.");
            b.start("player=Bartok", "count=3");
            b.run(20);
            b.check(b.acts().contains("spawn chicken x2 near Bartok → denied"), "the first summoning refused: " + b.acts());
            b.check(b.creatures().size() == 1, "the second one came: " + b.creatures());
            b.run(ticks(PAUSE_MS) - 40);
            b.check(b.creatures().size() == 1, "it waits: " + b.creatures());
            b.run(40);
            b.check(b.creatures().size() == 3, "asked again after the pause: " + b.creatures());
        }

        public void itIsCalledOffWhenNobodyMayMarch(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            for (int i = 0; i < 6; i++) b.refuseNext("spawn", "DENIED (chaos_0): the chaos level is 0: the Narrator summons nothing.");
            b.start("player=Bartok", "count=3");
            b.run(ticks(PAUSE_MS) + ticks(2 * PAUSE_MS) + 100);
            b.check(CALLED_OFF.equals(b.ended()), "called off after three refusals, 30 s then 1 min apart: " + b.ended());
        }

        private static int ticks(long millis) {
            return (int) (millis * Limits.TICKS_PER_SECOND / 1000);
        }
    }
}
