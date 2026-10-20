import io.github.clemsix6.narrator2.forge.api.*;
import io.github.clemsix6.narrator2.forge.api.test.Bench;
import java.util.*;

/**
 * Simon says, « Jacques a dit »: every 15 seconds an order shows above the hotbar (sneak, jump, look at the sky,
 * freeze), with or without « Jacques a dit ». Whoever does what Jacques did not say, or does not do what he said, is
 * out; the last one in wins, and so does the judged challenge it referees, if any.
 *
 * <p>What it shows: a state machine (rest, order and watch, verdict) run by one repeating timer; reads of PlayerInfo
 * (sneaking, on the ground, pitch, where they stand); hud lines kept within their limits; tellDirector; Game.win,
 * then ctx.end once the verdict is done; what a restart of the server needs, saved in stop.
 */
@ModuleInfo(name = "simon_says", summary = "Simon says (Jacques a dit): an order every 15 s; whoever gets it wrong is out, the last one in wins.")
@Param(name = "players", type = ParamType.PLAYERS, doc = "Who plays.", min = 2, max = 8)
@Param(name = "rounds", type = ParamType.INTEGER, doc = "How many orders at most; the players still in then win together.", required = false, fallback = "8", min = 3, max = 12)
public final class SimonSays implements ForgeModule {
    /** Ticks between two looks at the players. */
    private static final int STEP = 5;

    /** The first order, once the rules are read: 10 s after the start. */
    private static final int FIRST_ORDER = 10 * Limits.TICKS_PER_SECOND;

    /** Then an order every 15 s. */
    private static final int ORDER_EVERY = 15 * Limits.TICKS_PER_SECOND;

    /** The verdict, 4 s after the order. */
    private static final int WATCH = 4 * Limits.TICKS_PER_SECOND;

    /** Each player's line again every 25 ticks (1.25 s): hud shows one line a second per player at most. */
    private static final int LINE = 25;

    /** No line in the last 1.5 s before an order: the order shows at once, every player's last line being old enough. */
    private static final int QUIET = 30;

    /** The longest line above the hotbar (Acts.hud). */
    private static final int LINE_CHARS = 80;

    /** Looking at the sky: a pitch of 45 degrees up at least. */
    private static final float LOOK_UP_PITCH = -45;

    /** Further than this from where they stood, in blocks, a player has moved. */
    private static final double STILL_BLOCKS = 0.5;

    /** The game ends this long before the deployment does, for its verdict to be settled in time. */
    private static final long END_MARGIN_MS = 15_000;

    /** An order: what players read, how long they have to react, and how a look at a player tells whether they obey. */
    enum Order {
        SNEAK("accroupis-toi", 10),
        JUMP("saute", 10),
        LOOK_UP("regarde le ciel", 10),
        FREEZE("ne bouge plus", 20);

        final String words;

        /** Ticks from the order to the first look: half a second to react, a second to stop moving. */
        final int reaction;

        Order(String words, int reaction) {
            this.words = words;
            this.reaction = reaction;
        }

        /** Whether {@code now} obeys, {@code from} being how they stood at the first look. */
        boolean obeys(PlayerInfo from, PlayerInfo now) {
            return switch (this) {
                case SNEAK -> now.sneaking();
                case JUMP -> !now.onGround();
                case LOOK_UP -> now.pitch() <= LOOK_UP_PITCH;
                case FREEZE -> now.at().distance(from.at()) <= STILL_BLOCKS;
            };
        }

        /** A freeze is obeyed by holding still at every look; the others by doing it at one look at least. */
        boolean held() {
            return this == FREEZE;
        }
    }

    enum Phase { RESTING, WATCHING, OVER }

    private ModuleContext ctx;
    private List<String> players;
    private int rounds;

    /** The players still in, in the order of the parameters. */
    private final List<String> playing = new ArrayList<>();

    /** The players out, and why, as their line says it. */
    private final Map<String, String> out = new LinkedHashMap<>();

    private Phase phase = Phase.RESTING;
    private int ticks;
    private int round;
    private int orderAt = FIRST_ORDER;
    private Order order;
    private boolean jacques;

    /** How each player stood at the first look of the watch, and whether they obey so far. */
    private final Map<String, PlayerInfo> from = new HashMap<>();
    private final Map<String, Boolean> obeyed = new HashMap<>();

    /** The last verdict, on the lines of the players still in while they rest. */
    private String news = "Prêts ?";

    /** The tick each player got their last line. */
    private final Map<String, Integer> shownAt = new HashMap<>();

    @Override
    public void start(ModuleContext ctx) {
        this.ctx = ctx;
        players = ctx.args().players("players");
        rounds = ctx.args().integer("rounds");
        playing.addAll(players);
        if (ctx.restarted()) {
            resume();
        } else {
            ctx.acts().say("Jacques a dit : on joue. Un ordre toutes les 15s au-dessus de la barre d'objets ; obéissez seulement "
                + "quand Jacques l'a dit. Une erreur, et c'est la sortie.");
        }
        ctx.timers().every(STEP, this::step);
    }

    @Override
    public void stop(ModuleContext ctx, StopReason why) {
        if (why != StopReason.SERVER_STOPPING) return;
        // A restart starts a new instance with the storage only: keep how many orders were given and who is out.
        ctx.storage().putInt("round", round);
        for (Map.Entry<String, String> player : out.entrySet()) ctx.storage().put("out:" + player.getKey(), player.getValue());
    }

    /** The game as stop saved it; the next order comes after a rest, as at the start. */
    private void resume() {
        round = ctx.storage().getInt("round", 0);
        for (String player : players) {
            String why = ctx.storage().get("out:" + player);
            if (why == null) continue;
            playing.remove(player);
            out.put(player, why);
        }
        news = "On reprend";
    }

    /** Every STEP ticks: the state machine, then the lines. */
    private void step() {
        if (phase == Phase.OVER) return;
        ticks += STEP;
        if (ctx.now() >= ctx.endsAt() - END_MARGIN_MS) {
            finish(); // the deployment ends soon: the players still in win
            return;
        }
        Map<String, PlayerInfo> online = new HashMap<>();
        for (String player : players) {
            PlayerInfo info = ctx.world().player(player);
            if (info != null) online.put(player, info);
        }
        if (phase == Phase.RESTING && ticks >= orderAt) {
            if (decided()) {
                finish(); // after a restart, the game may be decided already
                return;
            }
            give();
        }
        if (phase == Phase.WATCHING) {
            if (ticks >= orderAt + order.reaction) watch(online);
            if (ticks >= orderAt + WATCH) judge();
        }
        if (phase != Phase.OVER) showLines(online);
    }

    /** A new order, with « Jacques a dit » three times out of five. */
    private void give() {
        round++;
        order = Order.values()[ctx.random().nextInt(Order.values().length)];
        jacques = ctx.random().nextInt(5) < 3;
        from.clear();
        obeyed.clear();
        phase = Phase.WATCHING;
    }

    /** A look at each player still in: does what they do obey the order? */
    private void watch(Map<String, PlayerInfo> online) {
        for (String player : playing) {
            PlayerInfo now = online.get(player);
            if (now == null) continue; // away: never seen, they are out at the verdict
            from.putIfAbsent(player, now);
            boolean obeys = order.obeys(from.get(player), now);
            Boolean before = obeyed.get(player);
            obeyed.put(player, before == null ? obeys : order.held() ? before && obeys : before || obeys);
        }
    }

    /** The verdict: whoever got it wrong is out, unless everyone did (then nobody is); whoever was away is out too. */
    private void judge() {
        List<String> wrong = new ArrayList<>();
        List<String> away = new ArrayList<>();
        for (String player : playing) {
            Boolean did = obeyed.get(player);
            if (did == null) away.add(player);
            else if (did != jacques) wrong.add(player);
        }
        boolean allFooled = !wrong.isEmpty() && wrong.size() + away.size() == playing.size();
        if (allFooled) wrong.clear();
        for (String player : away) out.put(player, "absent");
        for (String player : wrong) out.put(player, jacques ? "Jacques l'avait pourtant dit" : "Jacques n'avait rien dit");
        List<String> gone = new ArrayList<>(away);
        gone.addAll(wrong);
        for (String player : gone) playing.remove(player);
        String verdict = names(gone) + (gone.size() == 1 ? " sort" : " sortent");
        news = allFooled ? "Tous piégés, personne ne sort" : gone.isEmpty() ? "Sans faute" : verdict;
        if (!gone.isEmpty() && playing.size() > 1) ctx.tellDirector("Jacques a dit : " + verdict + ", il reste " + names(playing));
        if (decided()) {
            finish();
        } else {
            orderAt += ORDER_EVERY;
            phase = Phase.RESTING;
        }
    }

    /** One player left, or none, or every order given. */
    private boolean decided() {
        return playing.size() <= 1 || round >= rounds;
    }

    /** Each player online gets their line again once LINE ticks have passed, Limits.MAX_ACTS_PER_CALL a call at most. */
    private void showLines(Map<String, PlayerInfo> online) {
        if (phase == Phase.RESTING && orderAt - ticks <= QUIET) return;
        int lines = 0;
        for (String player : players) {
            Integer last = shownAt.get(player);
            if (!online.containsKey(player) || lines == Limits.MAX_ACTS_PER_CALL || (last != null && ticks - last < LINE)) continue;
            ctx.acts().hud(player, Text.cut(line(player), LINE_CHARS));
            shownAt.put(player, ticks);
            lines++;
        }
    }

    /** What {@code player} reads above the hotbar. */
    private String line(String player) {
        String why = out.get(player);
        if (why != null) return "Hors jeu (" + why + ") · " + playing.size() + " encore en lice";
        if (phase == Phase.WATCHING) {
            return jacques ? "Jacques a dit : " + order.words + " !" : Character.toUpperCase(order.words.charAt(0)) + order.words.substring(1) + " !";
        }
        return news + " · ordre suivant dans " + Text.duration((orderAt - ticks) * 1_000L / Limits.TICKS_PER_SECOND);
    }

    /** The game is decided: the players still in win; the verdict, then the end. */
    private void finish() {
        phase = Phase.OVER;
        List<String> winners = new ArrayList<>(playing);
        String note;
        String said;
        if (winners.isEmpty()) {
            note = "personne n'a tenu jusqu'au bout de Jacques a dit";
            said = "Jacques a dit : tout le monde dehors. Personne n'a tenu jusqu'au bout, Jacques est déçu.";
        } else if (winners.size() == 1) {
            note = winners.get(0) + " a tenu jusqu'au bout de Jacques a dit";
            said = "Jacques a dit : applaudissez " + winners.get(0) + ". Les autres réviseront la différence entre un ordre et une suggestion.";
        } else {
            note = names(winners) + " ont tenu jusqu'au bout de Jacques a dit";
            said = "Jacques a dit : " + names(winners) + " ont tenu jusqu'au bout. Match nul, mais avec les honneurs.";
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

    /** « Mimosa », « Mimosa et Josiane », « Bartok, Mimosa et Josiane ». */
    static String names(List<String> names) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) text.append(i == names.size() - 1 ? " et " : ", ");
            text.append(names.get(i));
        }
        return text.toString();
    }

    /** The orders are drawn at random: each scenario reads them above the hotbar, then plays them right or wrong. */
    public static final class Scenarios {
        private static final Map<String, Place> PLACES = Map.of(
            "Bartok", new Place("overworld", 0.5, 64, 0.5),
            "Mimosa", new Place("overworld", 6.5, 64, 0.5),
            "Josiane", new Place("overworld", 0.5, 64, 6.5));

        /** Bartok gets the first order wrong: he is out, the others read it, and the Director is told. */
        public void aWrongMoveIsOut(Bench b) {
            join(b, "Bartok", "Mimosa", "Josiane");
            b.start("players=Bartok,Mimosa,Josiane", "rounds=3");
            play(b, nextOrder(b, "Mimosa"), List.of("Mimosa", "Josiane"), List.of("Bartok"));
            b.check(line(b, "Bartok").startsWith("Hors jeu (Jacques"), "Bartok is out: " + line(b, "Bartok"));
            b.check(line(b, "Mimosa").startsWith("Bartok sort · "), "the others read it: " + line(b, "Mimosa"));
            b.check(b.notes().contains("director: Jacques a dit : Bartok sort, il reste Mimosa et Josiane"), "the Director is told: " + b.notes());
            b.check(b.ended() == null, "the game goes on with two players");
        }

        /** Of two players, the one who gets it right wins the challenge, then the game ends with its verdict. */
        public void theLastOneInWinsTheChallenge(Bench b) {
            join(b, "Bartok", "Mimosa");
            b.challenge("Bartok", "Mimosa");
            b.start("players=Bartok,Mimosa", "rounds=3");
            play(b, nextOrder(b, "Mimosa"), List.of("Mimosa"), List.of("Bartok"));
            b.check(b.acts().contains("game won: Mimosa"), "Mimosa wins the challenge: " + b.acts());
            b.check("Mimosa a tenu jusqu'au bout de Jacques a dit".equals(b.ended()), "it ends with its verdict: " + b.ended());
        }

        /** Everyone falls for the same order: nobody is out, and the game goes on. */
        public void whenEveryoneIsFooledNobodyIsOut(Bench b) {
            join(b, "Bartok", "Mimosa");
            b.start("players=Bartok,Mimosa", "rounds=3");
            play(b, nextOrder(b, "Mimosa"), List.of(), List.of("Bartok", "Mimosa"));
            b.check(line(b, "Bartok").startsWith("Tous piégés, personne ne sort · "), "nobody is out: " + line(b, "Bartok"));
            b.check(b.ended() == null, "the game goes on");
        }

        /** The verdict is refused (the challenge was over already): the module does not insist, and ends all the same. */
        public void aRefusedVerdictStillEndsTheGame(Bench b) {
            join(b, "Bartok", "Mimosa");
            b.challenge("Bartok", "Mimosa");
            b.refuseNext("game", "Invalid arguments: C-2 is not one of your running challenges; running: none");
            b.start("players=Bartok,Mimosa", "rounds=3");
            play(b, nextOrder(b, "Mimosa"), List.of("Mimosa"), List.of("Bartok"));
            b.check(b.acts().contains("game won: Mimosa → invalid"), "the verdict is refused: " + b.acts());
            b.check(b.ended() != null, "the game ends all the same");
        }

        private static void join(Bench b, String... names) {
            for (String name : names) b.player(name, PLACES.get(name));
        }

        /** Runs until an order shows above the hotbar of {@code player} (a line without « · »), and returns it. */
        private static String nextOrder(Bench b, String player) {
            for (int i = 0; i < 100; i++) {
                b.run(5);
                String line = line(b, player);
                if (!line.isEmpty() && !line.contains("·")) return line;
            }
            b.check(false, "an order shows above the hotbar of " + player + ": " + b.acts());
            return "";
        }

        /**
         * The watch of {@code order}, just shown: the {@code right} players obey Jacques and nobody else, the
         * {@code wrong} ones do the reverse; then everyone stands still again, and the verdict is shown.
         */
        private static void play(Bench b, String order, List<String> right, List<String> wrong) {
            String words = Text.normalize(order);
            boolean jacques = words.startsWith("jacques a dit");
            List<String> everyone = new ArrayList<>(right);
            everyone.addAll(wrong);
            List<String> moving = new ArrayList<>();
            for (String name : everyone) {
                boolean doIt = jacques == right.contains(name);
                if (words.contains("bouge")) {
                    if (!doIt) moving.add(name); // not freezing is moving
                } else {
                    b.pose(name, doIt && words.contains("accroupis"), !(doIt && words.contains("saute")), doIt && words.contains("ciel") ? -80 : 0);
                }
            }
            for (int t = 0; t < 120; t += 10) {
                for (String name : moving) b.move(name, PLACES.get(name).offset(t % 20 == 0 ? 2 : 0, 0, 0));
                b.run(10);
            }
            for (String name : everyone) {
                b.pose(name, false, true, 0);
                b.move(name, PLACES.get(name));
            }
        }

        /** The last line above the hotbar of {@code player}; empty when none. */
        private static String line(Bench b, String player) {
            String prefix = "hud " + player + ": ";
            List<String> acts = b.acts();
            for (int i = acts.size() - 1; i >= 0; i--) {
                if (acts.get(i).startsWith(prefix)) return acts.get(i).substring(prefix.length());
            }
            return "";
        }
    }
}
