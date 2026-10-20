import io.github.clemsix6.narrator2.forge.api.*;
import io.github.clemsix6.narrator2.forge.api.test.Bench;
import java.util.*;

/**
 * Hot or cold. A spot is hidden on the ground near one of the players; once a second, each player's action bar says
 * whether they got warmer or colder; whoever reaches the spot gets fireworks and wins (the judged challenge too, when
 * the module referees one). The spot is kept in the storage: after a restart of the server, it is the same spot.
 */
@ModuleInfo(name = "hot_cold", summary = "Hot or cold: a spot hidden near a player, warmer or colder in the action bar; who reaches it wins (and its challenge).")
@Param(name = "players", type = ParamType.PLAYERS, doc = "Who plays.", min = 1, max = 8)
@Param(name = "radius", type = ParamType.INTEGER, doc = "How far from a player the spot is hidden, in blocks.", required = false, fallback = "40", min = 20, max = 120)
public final class HotCold implements ForgeModule {
    /** Close enough to the spot to have found it, in blocks. */
    private static final double FOUND = 1.5;

    /** A change of distance smaller than this, in blocks, is neither warmer nor colder. */
    private static final double STILL = 0.5;

    /** The spot is at most this many blocks above or below the player it is hidden near: not on a cliff, nor on the nether's roof. */
    private static final int MAX_CLIMB = 24;

    /** Places tried in one call to hide the spot; the next second tries again. */
    private static final int TRIES = 8;

    /** The game is lost this long before the deployment ends: acts asked in stop() are dropped, so the end comes first. */
    private static final long CLOSING_MS = 10_000;

    private ModuleContext ctx;
    private List<String> players;

    /** The free block above the ground where the spot is; null until it is hidden. */
    private Cell spot;

    private boolean over;

    /** Each player's distance to the spot at their last pulse. */
    private final Map<String, Double> lastDistance = new HashMap<>();

    @Override
    public void start(ModuleContext ctx) {
        this.ctx = ctx;
        players = ctx.args().players("players");
        // A new deployment hides a new spot; one that starts again after a restart finds its spot in the storage.
        spot = ctx.restarted() ? saved() : null;
        if (spot == null) {
            hide();
        } else {
            ctx.log("restarted: the spot is still at " + describe(spot));
            ctx.acts().say("Le serveur a cligné des yeux. Le trésor, lui, n'a pas bougé.");
        }
        // One timer per player: each run is a call of its own, with its own four acts (Limits.MAX_ACTS_PER_CALL),
        // however many play.
        for (String name : players) ctx.timers().every(Limits.TICKS_PER_SECOND, () -> pulse(name));
    }

    /** Once a second for [name]: found, or warmer or colder in their action bar. */
    private void pulse(String name) {
        if (over) return;
        if (spot == null) {
            hide();
            return;
        }
        if (ctx.now() >= ctx.endsAt() - CLOSING_MS) {
            giveUp();
            return;
        }
        PlayerInfo player = ctx.world().player(name);
        if (player == null) return;
        double distance = spot.center().distance(player.at());
        if (distance <= FOUND) {
            found(player.name());
        } else {
            ctx.acts().hud(player.name(), hint(name, distance));
        }
    }

    /**
     * Hides the spot near one of the players online, chosen at random, on ground they can walk to, and keeps it in
     * the storage. Nobody online, or no good place in the loaded chunks: nothing, and the next pulse tries again.
     */
    private void hide() {
        List<PlayerInfo> online = new ArrayList<>();
        for (String name : players) {
            PlayerInfo player = ctx.world().player(name);
            if (player != null) online.add(player);
        }
        if (online.isEmpty()) return;
        PlayerInfo near = online.get(ctx.random().nextInt(online.size()));
        int radius = ctx.args().integer("radius");
        for (int i = 0; i < TRIES && spot == null; i++) {
            double angle = ctx.random().nextDouble() * 2 * Math.PI;
            double distance = radius * (0.5 + 0.5 * ctx.random().nextDouble());
            int x = (int) Math.floor(near.at().x() + Math.cos(angle) * distance);
            int z = (int) Math.floor(near.at().z() + Math.sin(angle) * distance);
            spot = groundAt(near.at(), x, z);
        }
        if (spot == null) return;
        Storage storage = ctx.storage();
        storage.put("dimension", spot.dimension());
        storage.putInt("x", spot.x());
        storage.putInt("y", spot.y());
        storage.putInt("z", spot.z());
        ctx.log("spot hidden at " + describe(spot) + ", near " + near.name());
        ctx.acts().say("J'ai caché un trésor près de " + near.name() + ". Ce n'est pas de l'or, c'est mieux : ma considération. "
            + "Votre barre d'action vous dira si vous chauffez.");
    }

    /**
     * The free block on the surface at {@code x}, {@code z}, in the dimension of {@code from}; null when its chunk is
     * not loaded, when it is too high or too low, or when the ground under it is no ground: water (the surface counts
     * it, and it is not solid) or the leaves of a tree top.
     */
    private Cell groundAt(Place from, int x, int z) {
        int y = ctx.world().surface(from.dimension(), x, z);
        if (y == Integer.MIN_VALUE || Math.abs(y - from.y()) > MAX_CLIMB) return null;
        BlockInfo ground = ctx.world().block(new Cell(from.dimension(), x, y - 1, z));
        if (ground == null || !ground.solid() || ground.block().endsWith("leaves")) return null;
        return new Cell(from.dimension(), x, y, z);
    }

    /** The spot kept in the storage; null when none was hidden yet. */
    private Cell saved() {
        Storage storage = ctx.storage();
        String dimension = storage.get("dimension");
        if (dimension == null) return null;
        return new Cell(dimension, storage.getInt("x", 0), storage.getInt("y", 0), storage.getInt("z", 0));
    }

    /** Warmer or colder than at the last pulse, and how warm. */
    private String hint(String name, double distance) {
        if (Double.isInfinite(distance)) return "Glacial. Mauvaise dimension, pour commencer.";
        Double before = lastDistance.put(name, distance);
        String trend;
        if (before == null || Math.abs(distance - before) < STILL) {
            trend = "Tu piétines";
        } else if (distance < before) {
            trend = "Tu chauffes";
        } else {
            trend = "Tu refroidis";
        }
        return trend + " · " + warmth(distance);
    }

    private static String warmth(double distance) {
        if (distance < 5) return "brûlant !";
        if (distance < 12) return "chaud";
        if (distance < 25) return "tiède";
        if (distance < 50) return "frais";
        return "glacial";
    }

    /**
     * {@code winner} reached the spot: fireworks, the news, the challenge won when it referees one, then the end. The
     * end waits for the last act's outcome: acts asked in the same call as {@code ctx.end} would be dropped.
     */
    private void found(String winner) {
        over = true;
        String why = winner + " a trouvé le trésor caché.";
        ctx.acts().fireworks(spot.center(), 3);
        Pending last = ctx.acts().say(winner + " a trouvé ! Applaudissez, ou faites au moins semblant.");
        Game game = ctx.game();
        if (game != null && game.running()) last = game.win(List.of(winner), why);
        last.then(outcome -> ctx.end(why));
    }

    /** Time is nearly up and nobody found it: the challenge lost when it referees one, then the end. */
    private void giveUp() {
        over = true;
        String why = "Personne n'a trouvé le trésor caché.";
        Pending last = ctx.acts().say("Temps écoulé. Le trésor reste où il est, vexé que personne ne soit venu.");
        Game game = ctx.game();
        if (game != null && game.running()) last = game.lose(why);
        last.then(outcome -> ctx.end(why));
    }

    private static String describe(Cell cell) {
        return "x=" + cell.x() + " y=" + cell.y() + " z=" + cell.z() + " in " + cell.dimension();
    }

    public static final class Scenarios {
        private static final Place START = new Place("overworld", 0.5, 64, 0.5);

        /** Bartok walks to the spot: warmer on the way, then fireworks, the challenge won and the end. */
        public void theFinderWinsTheChallenge(Bench b) {
            b.player("Bartok", START);
            b.challenge("Bartok");
            b.start("players=Bartok", "radius=20");
            Cell spot = logged(b, "spot hidden at");
            b.check(spot.y() == 64, "the spot is on the ground: " + spot);
            b.run(Limits.TICKS_PER_SECOND);
            b.move("Bartok", new Place("overworld", (START.x() + spot.x()) / 2, 64, (START.z() + spot.z()) / 2));
            b.run(Limits.TICKS_PER_SECOND);
            b.check(lastHud(b).startsWith("Tu chauffes"), "warmer halfway: " + lastHud(b));
            b.move("Bartok", spot.center());
            b.run(Limits.TICKS_PER_SECOND + 5);
            b.check(b.acts().stream().anyMatch(act -> act.startsWith("fireworks x3")), "fireworks over the spot: " + b.acts());
            b.check(b.acts().contains("game won: Bartok"), "the challenge won: " + b.acts());
            b.check(b.ended() != null && b.ended().startsWith("Bartok a trouvé"), "the end, after the acts: " + b.ended());
        }

        /** After a restart the spot comes back from the storage: the same one, not hidden again, and still to be found. */
        public void theSpotSurvivesARestart(Bench b) {
            b.player("Bartok", START);
            b.start("players=Bartok", "radius=20");
            Cell spot = logged(b, "spot hidden at");
            b.run(Limits.TICKS_PER_SECOND);
            b.restart();
            b.check(spot.equals(logged(b, "the spot is still at")), "the same spot after the restart: " + b.notes());
            b.run(Limits.TICKS_PER_SECOND);
            b.check(b.acts().stream().filter(act -> act.startsWith("say: J'ai caché")).count() == 1, "hidden once only: " + b.acts());
            b.move("Bartok", spot.center());
            b.run(Limits.TICKS_PER_SECOND + 5);
            b.check(b.ended() != null && b.ended().startsWith("Bartok a trouvé"), "found after the restart: " + b.ended());
        }

        /** Nobody comes: shortly before the deployment ends, the challenge is lost and the module ends itself. */
        public void nobodyFindsItInTime(Bench b) {
            b.player("Bartok", START);
            b.challenge("Bartok");
            b.start("players=Bartok");
            b.run(10 * 60 * Limits.TICKS_PER_SECOND - 5 * Limits.TICKS_PER_SECOND);
            b.check(b.acts().contains("game lost"), "the challenge lost");
            b.check("Personne n'a trouvé le trésor caché.".equals(b.ended()), "it ends itself before its time runs out: " + b.ended());
        }

        /** The spot of the module's log line that holds {@code words}: "spot hidden at x=12 y=64 z=-7 in overworld". */
        private static Cell logged(Bench b, String words) {
            for (String note : b.notes()) {
                if (note.contains(words)) return new Cell("overworld", number(note, "x="), number(note, "y="), number(note, "z="));
            }
            b.check(false, "no log line with \"" + words + "\": " + b.notes());
            return null;
        }

        /** The whole number written right after {@code label} in {@code line}. */
        private static int number(String line, String label) {
            int start = line.indexOf(label) + label.length();
            int end = start;
            while (end < line.length() && (line.charAt(end) == '-' || Character.isDigit(line.charAt(end)))) end++;
            return Integer.parseInt(line.substring(start, end));
        }

        /** The last line Bartok's action bar showed. */
        private static String lastHud(Bench b) {
            String last = "";
            for (String act : b.acts()) {
                if (act.startsWith("hud Bartok: ")) last = act.substring("hud Bartok: ".length());
            }
            return last;
        }
    }
}
