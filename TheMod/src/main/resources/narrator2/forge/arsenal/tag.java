import io.github.clemsix6.narrator2.forge.api.*;
import io.github.clemsix6.narrator2.forge.api.test.Bench;
import java.util.*;

/**
 * Tag, « le loup »: one player is the wolf, and coming within 1.5 blocks of another passes the role on. Particles
 * float above the wolf; each player's action bar shows who the wolf is, their own time as the wolf and the time left.
 * When the time is up, whoever was the wolf the least wins, and so does the judged challenge it referees, if any.
 *
 * <p>What it shows: the game's state in fields; one repeating timer that does a little every few ticks; a read of
 * World.players; hud lines and particles kept within the acts' limits; Game.win, then ctx.end once the verdict is
 * done; what a restart of the server needs, saved in stop.
 */
@ModuleInfo(name = "tag", summary = "Tag: whoever the wolf touches becomes the wolf; whoever was the wolf the least wins.")
@Param(name = "players", type = ParamType.PLAYERS, doc = "Who plays.", min = 2, max = 8)
@Param(name = "minutes", type = ParamType.INTEGER, doc = "How long the game lasts.", required = false, fallback = "5", min = 2, max = 15)
public final class Tag implements ForgeModule {
    /** Ticks between two looks at the players: four a second, enough to catch a touch. */
    private static final int STEP = 5;

    /** How close the wolf comes to pass the role on, in blocks. */
    private static final double REACH = 1.5;

    /** A new wolf counts to three before touching anyone: the others get away. */
    private static final long GRACE_MS = 3_000;

    /** Particles above the wolf every 3 s: 20 acts a minute, well within Limits.MAX_ACTS_PER_MINUTE. */
    private static final long MARK_MS = 3_000;

    /** Each player's line again every 1.25 s: hud shows one line a second per player at most. */
    private static final long LINE_MS = 1_250;

    /** Lines in one call: with the particles, Limits.MAX_ACTS_PER_CALL acts at most. */
    private static final int LINES_PER_CALL = Limits.MAX_ACTS_PER_CALL - 1;

    /** The longest line above the hotbar (Acts.hud). */
    private static final int LINE_CHARS = 80;

    /** The game ends this long before the deployment does, for its verdict to be settled in time. */
    private static final long END_MARGIN_MS = 15_000;

    private ModuleContext ctx;
    private List<String> players;

    /** How long each player has been the wolf, in milliseconds. */
    private final Map<String, Long> wolfMillis = new HashMap<>();

    /** The players seen online during the game: only they can win. */
    private final Set<String> seen = new HashSet<>();

    /** When each player got their last line. */
    private final Map<String, Long> shownAt = new HashMap<>();

    private String wolf;
    private long finishAt;
    private long lastLook;
    private long graceUntil;
    private long markedAt;
    private boolean over;

    @Override
    public void start(ModuleContext ctx) {
        this.ctx = ctx;
        players = ctx.args().players("players");
        long now = ctx.now();
        boolean resumed = ctx.restarted() && ctx.storage().get("wolf") != null;
        long length = resumed ? resume() : ctx.args().integer("minutes") * 60_000L;
        finishAt = Math.min(now + length, ctx.endsAt() - END_MARGIN_MS);
        if (!resumed) {
            wolf = players.get(ctx.random().nextInt(players.size()));
            ctx.acts().say(wolf + " est le loup, et ce n'est pas un compliment. Approchez-vous à un pas d'un autre joueur pour lui "
                + "refiler le rôle. Dans " + Text.duration(finishAt - now) + ", le moins longtemps loup gagne.");
        }
        lastLook = now;
        graceUntil = now + GRACE_MS;
        markedAt = now - MARK_MS;
        ctx.timers().every(STEP, this::look);
    }

    @Override
    public void stop(ModuleContext ctx, StopReason why) {
        if (why != StopReason.SERVER_STOPPING) return;
        // A restart starts a new instance with the storage only: keep what the game needs to go on.
        ctx.storage().put("wolf", wolf);
        ctx.storage().putDouble("left", Math.max(0, finishAt - ctx.now()));
        for (String player : seen) ctx.storage().putDouble("ms:" + player, timeOf(player));
    }

    /** The game as stop saved it: the wolf and each one's time; the time it had left. */
    private long resume() {
        wolf = ctx.storage().get("wolf");
        for (String player : players) {
            String key = "ms:" + player;
            if (ctx.storage().get(key) == null) continue;
            seen.add(player);
            wolfMillis.put(player, (long) ctx.storage().getDouble(key, 0));
        }
        return (long) ctx.storage().getDouble("left", 0);
    }

    /** Every STEP ticks: the wolf's time, a touch, the particles, the lines. */
    private void look() {
        if (over) return;
        long now = Math.min(ctx.now(), finishAt); // no time counts past the end
        Map<String, PlayerInfo> online = online();
        seen.addAll(online.keySet());
        PlayerInfo it = online.get(wolf);
        if (it != null) wolfMillis.merge(wolf, now - lastLook, Long::sum); // a wolf who is away does not count
        lastLook = now;
        if (now >= finishAt) {
            finish();
            return;
        }
        if (it == null && !online.isEmpty()) {
            pass(any(online), now); // the wolf left: someone else takes the role
        } else if (it != null && now >= graceUntil) {
            String touched = touched(it, online);
            if (touched != null) pass(touched, now);
        }
        PlayerInfo marked = online.get(wolf);
        if (marked != null && now - markedAt >= MARK_MS) {
            ctx.acts().particles(marked.at().offset(0, 2.5, 0), "angry_villager", 6);
            markedAt = now;
        }
        showLines(online, now);
    }

    /** The players of the game who are online, by their name in the parameters, in the order they joined the server. */
    private Map<String, PlayerInfo> online() {
        Map<String, PlayerInfo> online = new LinkedHashMap<>();
        for (PlayerInfo player : ctx.world().players()) {
            for (String name : players) {
                if (name.equalsIgnoreCase(player.name())) online.put(name, player);
            }
        }
        return online;
    }

    /** The player the wolf touches: the nearest within REACH; null when none is. */
    private String touched(PlayerInfo it, Map<String, PlayerInfo> online) {
        String nearest = null;
        double best = REACH;
        for (Map.Entry<String, PlayerInfo> other : online.entrySet()) {
            double distance = it.at().distance(other.getValue().at());
            if (!other.getKey().equals(wolf) && distance <= best) {
                nearest = other.getKey();
                best = distance;
            }
        }
        return nearest;
    }

    /** One of the players online, at random. */
    private String any(Map<String, PlayerInfo> online) {
        List<String> names = new ArrayList<>(online.keySet());
        return names.get(ctx.random().nextInt(names.size()));
    }

    /** {@code to} becomes the wolf, and counts to three. */
    private void pass(String to, long now) {
        ctx.log(wolf + " passes the wolf on to " + to);
        wolf = to;
        graceUntil = now + GRACE_MS;
    }

    /** Each player online gets their line again once LINE_MS have passed, LINES_PER_CALL a call at most. */
    private void showLines(Map<String, PlayerInfo> online, long now) {
        int lines = 0;
        for (String player : online.keySet()) {
            Long last = shownAt.get(player);
            if (lines == LINES_PER_CALL || (last != null && now - last < LINE_MS)) continue;
            ctx.acts().hud(player, Text.cut(line(player, now), LINE_CHARS));
            shownAt.put(player, now);
            lines++;
        }
    }

    /** What {@code player} reads above the hotbar. */
    private String line(String player, long now) {
        String left = " · fin dans " + Text.duration(finishAt - now);
        if (!player.equals(wolf)) return "Loup : " + wolf + " · toi : " + Text.duration(timeOf(player)) + left;
        if (now < graceUntil) return "Tu es le loup ! Compte jusqu'à trois…";
        return "Tu es le loup · " + Text.duration(timeOf(player)) + left;
    }

    /** Time is up: whoever was the wolf the least wins; the verdict, then the end. */
    private void finish() {
        over = true;
        List<String> winners = leastWolves();
        String note;
        String said;
        if (winners.isEmpty()) {
            note = "personne n'a joué au loup";
            said = "Fin du loup : personne n'est venu jouer. Le loup a couru tout seul.";
        } else {
            String time = Text.duration(timeOf(winners.get(0)));
            String most = mostWolf();
            boolean one = winners.size() == 1;
            note = names(winners) + (one ? " a été le moins longtemps loup (" : " ont été les moins longtemps loups (") + time + ")";
            said = "Fin du loup. " + names(winners) + (one ? " gagne" : " gagnent") + " avec " + time + " de pelage seulement.";
            if (!winners.contains(most)) said += " " + most + " l'a porté " + Text.duration(timeOf(most)) + " : il lui en restera des poils.";
        }
        Pending last = ctx.acts().say(Text.cut(said, 300));
        Game game = ctx.game();
        if (game != null && game.running()) last = winners.isEmpty() ? game.lose(note) : game.win(winners, note);
        // Ending in this same call would drop what is still queued, the verdict above: end once it is done.
        last.then(outcome -> {
            if (!outcome.ok()) ctx.log("the last act was not done: " + outcome.text());
            ctx.end(note);
        });
    }

    /** Who was the wolf the least among those who played; several on a tie. */
    private List<String> leastWolves() {
        List<String> least = new ArrayList<>();
        for (String player : players) {
            if (!seen.contains(player)) continue;
            if (!least.isEmpty() && timeOf(player) < timeOf(least.get(0))) least.clear();
            if (least.isEmpty() || timeOf(player) == timeOf(least.get(0))) least.add(player);
        }
        return least;
    }

    /** Who was the wolf the longest. */
    private String mostWolf() {
        String most = players.get(0);
        for (String player : players) {
            if (timeOf(player) > timeOf(most)) most = player;
        }
        return most;
    }

    private long timeOf(String player) {
        return wolfMillis.getOrDefault(player, 0L);
    }

    /** « Mimosa », « Mimosa et Josiane », « Bartok, Mimosa et Josiane ». */
    static String names(List<String> names) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) text.append(i == names.size() - 1 ? " et " : ", ");
            text.append(names.get(i));
        }
        return text.toString();
    }

    /** Bartok and Mimosa, ten blocks apart; the wolf is drawn at random, so each scenario reads it on the action bar. */
    public static final class Scenarios {
        private static final Place BARTOK = new Place("overworld", 0.5, 64, 0.5);
        private static final Place MIMOSA = new Place("overworld", 10.5, 64, 0.5);

        /** Once the first wolf has counted to three, coming close passes the role on, and the particles follow. */
        public void aTouchPassesTheRoleOn(Bench b) {
            b.player("Bartok", BARTOK);
            b.player("Mimosa", MIMOSA);
            b.start("players=Bartok,Mimosa", "minutes=2");
            b.run(80);
            String wolf = wolf(b);
            b.check(b.acts().contains(particlesOver(at(wolf))), "particles over the wolf, " + wolf);
            String prey = other(wolf);
            Place close = at(wolf).offset(1, 0, 0);
            b.move(prey, close);
            b.run(30);
            b.check(prey.equals(wolf(b)), "the touch makes " + prey + " the wolf: " + b.acts());
            b.move(wolf, at(wolf).offset(-8, 0, 0)); // the old wolf runs away
            b.run(60);
            b.check(b.acts().contains(particlesOver(close)), "the particles follow the new wolf");
        }

        /** Nobody is ever touched: the other player wins the challenge, then the game ends with its verdict. */
        public void theLeastWolfWinsTheChallenge(Bench b) {
            b.player("Bartok", BARTOK);
            b.player("Mimosa", MIMOSA);
            b.challenge("Bartok", "Mimosa");
            b.start("players=Bartok,Mimosa", "minutes=2");
            b.run(20);
            String winner = other(wolf(b));
            b.run(2 * 60 * Limits.TICKS_PER_SECOND);
            b.check(b.acts().contains("game won: " + winner), winner + " wins the challenge: " + b.acts());
            b.check((winner + " a été le moins longtemps loup (0s)").equals(b.ended()), "it ends with its verdict: " + b.ended());
        }

        /** The verdict is refused (the challenge was over already): the module does not insist, and ends all the same. */
        public void aRefusedVerdictStillEndsTheGame(Bench b) {
            b.player("Bartok", BARTOK);
            b.player("Mimosa", MIMOSA);
            b.challenge("Bartok", "Mimosa");
            b.refuseNext("game", "Invalid arguments: C-2 is not one of your running challenges; running: none");
            b.start("players=Bartok,Mimosa", "minutes=2");
            b.run(2 * 60 * Limits.TICKS_PER_SECOND + 20);
            b.check(b.acts().stream().anyMatch(act -> act.startsWith("game won: ") && act.endsWith(" → invalid")), "the verdict is refused: " + b.acts());
            b.check(b.ended() != null, "the game ends all the same");
        }

        /** A restart of the server: the same wolf, and no second announcement. */
        public void aRestartGoesOnWithTheSameGame(Bench b) {
            b.player("Bartok", BARTOK);
            b.player("Mimosa", MIMOSA);
            b.start("players=Bartok,Mimosa", "minutes=2");
            b.run(100);
            String wolf = wolf(b);
            b.restart();
            b.run(40);
            b.check(wolf.equals(wolf(b)), "still " + wolf + " after the restart: " + b.acts());
            b.check(b.acts().stream().filter(act -> act.startsWith("say: ")).count() == 1, "announced once: " + b.acts());
        }

        /** The wolf, as the last line above the hotbar tells it: « Tu es le loup… » to the wolf, « Loup : Bartok… » to the others. */
        private static String wolf(Bench b) {
            List<String> acts = b.acts();
            for (int i = acts.size() - 1; i >= 0; i--) {
                String act = acts.get(i);
                int colon = act.indexOf(": ");
                if (!act.startsWith("hud ") || colon < 0) continue;
                String line = act.substring(colon + 2);
                if (line.startsWith("Tu es le loup")) return act.substring(4, colon);
                if (line.startsWith("Loup : ")) return line.substring(7, line.indexOf(" · "));
            }
            return "nobody";
        }

        private static String other(String player) {
            return player.equals("Bartok") ? "Mimosa" : "Bartok";
        }

        private static Place at(String player) {
            return player.equals("Bartok") ? BARTOK : MIMOSA;
        }

        /** The act of the particles above a player standing at {@code at}. */
        private static String particlesOver(Place at) {
            return "particles angry_villager x6 at " + at.x() + " " + (at.y() + 2.5) + " " + at.z();
        }
    }
}
