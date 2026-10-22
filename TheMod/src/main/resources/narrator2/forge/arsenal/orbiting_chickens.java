import io.github.clemsix6.narrator2.forge.api.*;
import io.github.clemsix6.narrator2.forge.api.test.Bench;
import java.util.List;

/**
 * Orbiting chickens: chickens circle a player at head height, then flutter down and vanish in a puff.
 *
 * <p>What it shows of the SDK: {@code spawn} and the creatures its outcome gives, two by two to stay within the
 * chaos level's caps; a creature steered with {@code velocity} at each tick; {@code gravity}; {@code release}; and a
 * clean end: the deployment ends in the last act's {@code then}, since the acts of the call that ends it are dropped.
 */
@ModuleInfo(name = "orbiting_chickens", summary = "Chickens circle a player at head height, then flutter down and vanish in a puff.")
@Param(name = "player", type = ParamType.PLAYER, doc = "Whom the chickens circle.")
@Param(name = "chickens", type = ParamType.INTEGER, doc = "How many chickens.", required = false, fallback = "6", min = 3, max = 8)
@Param(name = "seconds", type = ParamType.INTEGER, doc = "How long they circle before they come down.", required = false, fallback = "30", min = 10, max = 120)
public final class OrbitingChickens implements ForgeModule {
    /** Chickens one summoning asks for: the most one call of spawn may summon at the lowest chaos level. */
    static final int BATCH = 2;

    /** Minutes a summoned chicken lives: longer than the longest orbit and its landing. */
    static final int LIFETIME_MINUTES = 3;

    /** From the player's axis to the chickens, in blocks. */
    static final double RADIUS = 2.5;

    /** From the player's feet to the chickens' feet: their bodies at head height. */
    static final double HEIGHT = 1.4;

    /** How far each chicken bobs up and down as it circles. */
    static final double BOB = 0.2;

    /** Radians the circle turns each tick: a full turn every four seconds. */
    static final double TURN = 2 * Math.PI / (4 * Limits.TICKS_PER_SECOND);

    /** The share of the way to its spot a chicken flies each tick: half, for a smooth flight just behind its spot. */
    static final double STEER = 0.5;

    /** The fastest a chicken flies, in blocks a tick on each axis (velocity takes 2 at most). */
    static final double TOP_SPEED = 1;

    /** How long the landing lasts: once their gravity is back, chickens flutter down slowly by themselves. */
    static final int LANDING_TICKS = 3 * Limits.TICKS_PER_SECOND;

    static final String LANDED = "Les poules sont redescendues sur terre.";
    static final String REFUSED = "Les poules ont refusé de décoller.";

    private ModuleContext ctx;
    private String player;

    /** Summonings whose outcome has not come yet. */
    private int coming;

    private Scheduled orbit;
    private boolean landing;
    private String ending = LANDED;

    /** Ticks of the orbit so far: the circle's angle. */
    private int ticks;

    /** The player's feet at the last tick of the orbit: the middle of the circle. */
    private Place center;

    @Override
    public void start(ModuleContext ctx) {
        this.ctx = ctx;
        player = ctx.args().player("player");
        if (ctx.restarted()) {
            // Timers do not survive a restart: the chickens it gets back come down at once.
            land(LANDED);
            return;
        }
        // Two by two: eight chickens are four summonings, the four acts a call may ask.
        for (int left = ctx.args().integer("chickens"); left > 0; left -= BATCH) {
            coming++;
            Spawn how = Spawn.of(Math.min(BATCH, left)).withDistance(3).withHeight(2).withLifetimeMinutes(LIFETIME_MINUTES);
            ctx.acts().spawn("chicken", player, how).then(this::arrived);
        }
    }

    /** A summoning's outcome: its chickens float. Once the last one is in, the orbit begins, or the module ends without a chicken. */
    private void arrived(Outcome summoned) {
        coming--;
        if (!summoned.ok()) ctx.log("a summoning was refused: " + summoned.text());
        for (String uuid : summoned.creatures()) {
            Creature chicken = ctx.creatures().get(uuid);
            if (chicken != null) chicken.gravity(false);
        }
        if (coming > 0) return;
        if (ctx.creatures().own().isEmpty()) {
            ctx.end(REFUSED);
            return;
        }
        orbit = ctx.timers().every(1, this::circle);
        ctx.timers().after(ctx.args().integer("seconds") * Limits.TICKS_PER_SECOND, () -> land(LANDED));
        ctx.acts().title(player, "Mise en orbite", "Des poules gravitent autour de toi.");
        ctx.acts().sound(player, "entity.chicken.ambient");
    }

    /** Each tick: each chicken flies toward its spot on a circle around the player's head, and the circle turns. */
    private void circle() {
        PlayerInfo it = ctx.world().player(player);
        if (it == null || (center != null && !it.at().dimension().equals(center.dimension()))) {
            // Gone, or through a portal: the chickens cannot follow.
            land(player + " n'a pas attendu l'atterrissage de ses poules.");
            return;
        }
        center = it.at();
        List<Creature> flock = ctx.creatures().own();
        if (flock.isEmpty()) {
            ctx.end("Plus une poule en orbite : la mission spatiale a échoué.");
            return;
        }
        ticks++;
        for (int i = 0; i < flock.size(); i++) {
            Creature chicken = flock.get(i);
            CreatureInfo now = chicken.info();
            if (now == null) continue;
            double angle = ticks * TURN + 2 * Math.PI * i / flock.size();
            double x = center.x() + RADIUS * Math.cos(angle);
            double y = center.y() + HEIGHT + BOB * Math.sin(2 * angle);
            double z = center.z() + RADIUS * Math.sin(angle);
            chicken.velocity(steer(x - now.at().x()), steer(y - now.at().y()), steer(z - now.at().z()));
        }
    }

    /** A share of the gap to fly this tick, within the top speed. */
    private static double steer(double gap) {
        return Math.max(-TOP_SPEED, Math.min(TOP_SPEED, gap * STEER));
    }

    /** The orbit is over: the chickens stop and flutter down; once they have landed, they vanish and the module ends with {@code why}. */
    private void land(String why) {
        if (landing) return;
        landing = true;
        ending = why;
        if (orbit != null) orbit.cancel();
        for (Creature chicken : ctx.creatures().own()) {
            chicken.velocity(0, 0, 0);
            chicken.gravity(true);
        }
        ctx.timers().after(LANDING_TICKS, this::vanish);
    }

    /** Landed: released (summoned, they vanish) in a puff; the deployment ends once the puff is done. */
    private void vanish() {
        for (Creature chicken : ctx.creatures().own()) chicken.release();
        if (center == null) {
            ctx.end(ending);
            return;
        }
        // Not ctx.end right here: the acts of the call that ends the deployment are dropped, the puff with them.
        ctx.acts().particles(center.offset(0, HEIGHT, 0), "poof", 30).then(done -> ctx.end(ending));
    }

    /** At the bench there is no physics: a chicken moves by exactly its velocity, and gravity changes nothing. */
    public static final class Scenarios {
        public void theyCircleThePlayerAtHeadHeight(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.start("player=Bartok", "chickens=8", "seconds=20");
            b.run(40);
            b.check(b.acts().stream().filter(act -> act.equals("spawn chicken x2 near Bartok")).count() == 4, "summoned two by two: " + b.acts());
            b.check(b.acts().contains("title Bartok: Mise en orbite / Des poules gravitent autour de toi."), "told: " + b.acts());
            List<CreatureInfo> flock = b.creatures();
            b.check(flock.size() == 8, "eight chickens: " + flock);
            for (CreatureInfo chicken : flock) {
                b.check(Math.abs(radius(chicken) - RADIUS) < 0.3, "on the circle: " + chicken.at());
                b.check(Math.abs(chicken.at().y() - 64 - HEIGHT) < BOB + 0.1, "at head height: " + chicken.at());
            }
            double before = angle(flock.get(0));
            b.run(10);
            double turned = angle(b.creatures().get(0)) - before;
            if (turned < 0) turned += 2 * Math.PI;
            b.check(Math.abs(turned - 10 * TURN) < 0.1, "the circle turns, " + turned + " radians in 10 ticks");
        }

        public void theyComeDownThenVanishInAPuff(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.start("player=Bartok", "chickens=3", "seconds=10");
            b.run(10 * Limits.TICKS_PER_SECOND + 5);
            b.check(b.creatures().size() == 3 && b.ended() == null, "they are landing: " + b.creatures());
            b.run(LANDING_TICKS + 5);
            b.check(b.creatures().isEmpty(), "released, they vanished: " + b.creatures());
            b.check(b.acts().stream().anyMatch(act -> act.startsWith("particles poof x30 at 0.5 65.4 0.5")), "in a puff: " + b.acts());
            b.check(LANDED.equals(b.ended()), "it ends by itself once the puff is done: " + b.ended());
        }

        public void aRefusedSummoningEndsIt(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.refuseNext("spawn", "DENIED (chaos_0): the chaos level is 0: the Narrator summons nothing.");
            b.refuseNext("spawn", "DENIED (chaos_0): the chaos level is 0: the Narrator summons nothing.");
            b.start("player=Bartok", "chickens=3");
            b.run(5);
            b.check(b.acts().equals(List.of("spawn chicken x2 near Bartok → denied", "spawn chicken x1 near Bartok → denied")), "both refused: " + b.acts());
            b.check(b.creatures().isEmpty(), "no chicken: " + b.creatures());
            b.check(REFUSED.equals(b.ended()), "it ends without a chicken nor a title: " + b.ended());
        }

        public void theyLandWhenThePlayerLeaves(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.start("player=Bartok", "chickens=3", "seconds=60");
            b.run(40);
            b.leave("Bartok");
            b.run(LANDING_TICKS + 5);
            b.check(b.creatures().isEmpty(), "released, they vanished: " + b.creatures());
            b.check("Bartok n'a pas attendu l'atterrissage de ses poules.".equals(b.ended()), "it ends, Bartok gone: " + b.ended());
        }

        public void theyComeDownAfterARestart(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.start("player=Bartok", "chickens=3", "seconds=60");
            b.run(40);
            b.restart();
            b.check(b.creatures().size() == 3, "its chickens are back: " + b.creatures());
            b.run(LANDING_TICKS + 5);
            b.check(b.creatures().isEmpty() && LANDED.equals(b.ended()), "they came down at once, then it ended: " + b.ended());
        }

        private static double radius(CreatureInfo chicken) {
            return Math.hypot(chicken.at().x() - 0.5, chicken.at().z() - 0.5);
        }

        private static double angle(CreatureInfo chicken) {
            return Math.atan2(chicken.at().z() - 0.5, chicken.at().x() - 0.5);
        }
    }
}
