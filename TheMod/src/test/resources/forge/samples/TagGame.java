import io.github.clemsix6.narrator2.forge.api.*;
import io.github.clemsix6.narrator2.forge.api.test.Bench;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The wolf: the wolf passes on by touch, and whoever was the wolf the least wins. The witness of M5-01: a module as
 * the forger writes one, with what Java code commonly holds (records, enums, an enum switch, a string switch, a
 * pattern switch on the sealed facts, lambdas, method references, streams, a text block, anonymous and local classes,
 * assert, try/finally), compiled against the SDK and checked against the allow-list before the SDK was frozen.
 */
@ModuleInfo(name = "tag_game", summary = "Tag: the wolf passes on by touch; whoever was the wolf the least wins.")
@Param(name = "players", type = ParamType.PLAYERS, doc = "Who plays.", min = 2, max = 8)
@Param(name = "minutes", type = ParamType.INTEGER, doc = "How long it lasts.", required = false, fallback = "5", min = 2, max = 15)
@Param(name = "mood", type = ParamType.TEXT, doc = "How the Narrator speaks.", required = false, fallback = "taquin", choices = {"taquin", "sévère"})
public final class TagGame implements ForgeModule {
    enum Phase { WAITING, RUNNING, DONE }

    record Score(String player, int seconds) implements Comparable<Score> {
        @Override
        public int compareTo(Score other) {
            return Integer.compare(seconds, other.seconds);
        }
    }

    sealed interface Move {}

    record Touch(String from, String to) implements Move {}

    record Escape(String who, double distance) implements Move {}

    private final Map<String, Integer> seconds = new HashMap<>();
    private ModuleContext ctx;
    private Phase phase = Phase.WAITING;
    private List<String> players = List.of();
    private String wolf;

    @Override
    public void start(ModuleContext ctx) {
        this.ctx = ctx;
        players = ctx.args().players("players");
        int minutes = ctx.args().integer("minutes");
        assert minutes > 0 : "a round lasts";
        wolf = ctx.restarted() ? ctx.storage().get("wolf") : null;
        if (wolf == null) wolf = players.get(ctx.random().nextInt(players.size()));
        ctx.storage().put("wolf", wolf);
        phase = Phase.RUNNING;
        ctx.acts().say("« " + wolf + " » est le loup pour " + minutes + " minutes, " + greeting(ctx.args().text("mood"))).then(this::heard);
        ctx.events().on(GameFact.Chat.class, chat -> {
            switch (Text.normalize(chat.text())) {
                case "loup" -> ctx.acts().sayTo(chat.player(), wolf + " est le loup.");
                case "score" -> ctx.acts().sayTo(chat.player(), table());
                default -> { }
            }
        });
        ctx.events().on(GameFact.BlockBroken.class, this::onBreak);
        ctx.timers().every(Limits.TICKS_PER_SECOND, this::second);
        ctx.timers().after(minutes * 60 * Limits.TICKS_PER_SECOND, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
    }

    @Override
    public void stop(ModuleContext ctx, StopReason why) {
        if (why == StopReason.EXPIRED) ctx.log("the round ran out at " + label(phase));
    }

    private void second() {
        if (phase != Phase.RUNNING) return;
        seconds.merge(wolf, 1, Integer::sum);
        PlayerInfo it = ctx.world().player(wolf);
        if (it == null) return;
        for (String name : players) {
            PlayerInfo other = ctx.world().player(name);
            if (other != null && !name.equals(wolf) && other.at().distance(it.at()) <= 1.5) {
                ctx.log(tell(new Touch(wolf, name)));
                wolf = name;
                ctx.storage().put("wolf", wolf);
                ctx.acts().hud(null, describe(new GameFact.Chat(name, other.at(), "touché")));
                ctx.acts().particles(other.at().offset(0, 2.5, 0), "angry_villager", 10);
                break;
            }
        }
    }

    private void onBreak(GameFact.BlockBroken broken) {
        if (Text.words(broken.block()).contains("diamond")) seconds.merge(broken.player(), -2, Integer::sum);
    }

    private void heard(Outcome outcome) {
        if (!outcome.ok()) ctx.log("the start was not said: " + outcome.text());
    }

    private void finish() {
        if (phase == Phase.DONE) return;
        phase = Phase.DONE;
        Optional<Score> best = scores().stream().findFirst();
        String why = best.map(score -> score.player() + " a été le moins longtemps loup").orElse("personne n'a joué");
        Game game = ctx.game();
        if (game != null && game.running() && best.isPresent()) game.win(List.of(best.get().player()), why);
        ctx.end(why);
    }

    static String describe(GameFact fact) {
        return switch (fact) {
            case GameFact.Chat chat when chat.text().isEmpty() -> chat.player() + " se tait";
            case GameFact.Chat chat -> chat.player() + " devient le loup";
            case GameFact.BlockBroken broken -> broken.player() + " casse " + broken.block();
            default -> "rien";
        };
    }

    static String tell(Move move) {
        return switch (move) {
            case Touch touch -> touch.from() + " touche " + touch.to();
            case Escape escape when escape.distance() > 10 -> escape.who() + " s'enfuit";
            case Escape escape -> escape.who() + " recule";
        };
    }

    static String greeting(String mood) {
        return switch (mood) {
            case "sévère" -> "et pas de quartier.";
            default -> "amusez-vous bien.";
        };
    }

    private static String label(Phase phase) {
        switch (phase) {
            case WAITING:
                return "l'attente";
            case RUNNING:
                return "la partie";
            default:
                return "la fin";
        }
    }

    private List<Score> scores() {
        List<Score> scores = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : seconds.entrySet()) scores.add(new Score(entry.getKey(), entry.getValue()));
        Collections.sort(scores);
        return scores;
    }

    String table() {
        class Rank {
            int value;
        }
        Rank rank = new Rank();
        List<Score> scores = scores();
        String ranked = scores.stream().map(score -> (++rank.value) + ". " + score.player() + " " + score.seconds() + "s").collect(Collectors.joining(", "));
        int total = IntStream.range(0, scores.size()).map(i -> scores.get(i).seconds()).sum();
        char[] initials = new char[scores.size()];
        for (int i = 0; i < initials.length; i++) initials[i] = scores.get(i).player().charAt(0);
        String text = """
            Total : %d secondes (%s)""".formatted(total, new String(initials));
        StringBuilder line = new StringBuilder("Scores : ");
        try {
            return line.append(ranked).append(" · ").append(text.strip()).toString();
        } finally {
            phase = phase == Phase.DONE ? Phase.DONE : phase;
        }
    }

    public static final class Scenarios {
        public void theWolfPassesOnByTouch(Bench b) {
            b.player("Bartok", new Place("overworld", 0, 64, 0));
            b.player("Mimosa", new Place("overworld", 10, 64, 0));
            b.start("players=Bartok,Mimosa", "minutes=2");
            b.run(40);
            b.move("Mimosa", new Place("overworld", 0.5, 64, 0));
            b.move("Bartok", new Place("overworld", 0, 64, 0));
            b.run(20);
            b.check(b.acts().stream().anyMatch(act -> act.startsWith("hud")), "the touch is shown");
        }

        public void theLeastWolfWins(Bench b) {
            b.player("Bartok", new Place("overworld", 0, 64, 0));
            b.player("Mimosa", new Place("overworld", 30, 64, 0));
            b.challenge("Bartok", "Mimosa");
            b.start("players=Bartok,Mimosa", "minutes=2");
            b.run(2 * 60 * Limits.TICKS_PER_SECOND + 1);
            b.check(b.ended() != null, "it ends by itself");
        }
    }
}
