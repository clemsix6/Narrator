# The Forge SDK, version 1

A module is one Java 25 file compiled against this SDK and part of `java.base` only: no class of the game, of Fabric or of the mod; all it does goes through the Narrator's own tools and guards.

## 1. The file

- No `package` line. Imports: those of the example (§10), and what else of the JDK you use (§7).
- One `public final class` named after the module in UpperCamelCase (`word_race` → `WordRace`), `implements ForgeModule`, with a public constructor without arguments (or none). Nested classes, records, enums, pattern switches, lambdas, streams: fine.
- `@ModuleInfo(name = "word_race", summary = "…")`: the name, `[a-z][a-z0-9_]{2,31}`, is the tools' `name`; the summary, one English line (120 chars): what players see.
- `@Param(…)` once per parameter, 8 at most: a snake_case name; `doc`, one English line (120 chars); `required = false` takes its `fallback` (INTEGER, NUMBER and BOOLEAN need one); `min`, `max` bound an INTEGER or a NUMBER, how many PLAYERS (8 at most), how long a TEXT (300 at most); `choices`: a TEXT's only values. The module reads checked values only.
- Text forms (`fallback`, `Bench.start`): PLAYER `Bartok`; PLAYERS `Bartok,Mimosa`; INTEGER `6`; NUMBER `2.5`; TEXT as is; BOOLEAN `true`; PLACE `120 64 -30`, `120 64 -30 the_nether`, or an online player's name.
- `Scenarios`: a nested `public static final class Scenarios`, up to 8 methods `public void name(Bench b)`, helpers private or static; one at least for `forge_save` (§8).
- Code, comments, `summary`, `doc` and `log` lines are English; all that players or the Director read is French.

## 2. How a module runs

- Each deployment makes a new instance: constructor, then `start(ctx)`, 50 ms each (20 at the bench); keep `ctx` in a field. Then it runs only when called back: each fact handler, timer run and `then` callback is a call of its own, 20 ms at most, one at a time, the server waiting for it. Never loop to wait: schedule.
- Nothing blocks: acts are queued, done after the call; their `Outcome` comes a tick later.
- It ends when its time runs out (`ctx.endsAt()`, 5 minutes to 7 days), when it is stopped, when it calls `ctx.end(why)`, or when its faults disable it (§7). Then `stop(ctx, why)` runs once, never after a fault: reads work, acts are dropped. Its creatures are taken back after it.
- State lives in fields. After a server restart: a new instance, `start` with `ctx.restarted()` true: `ctx.storage()` back, creatures back as their chunks load, no timers nor subscriptions; the restart's facts were missed.
- Ids have no `minecraft:` (`cow`, `diamond_ore`, `the_nether`); `game_ids` lists them. Players are names, creatures UUIDs as text, times epoch ms, delays ticks (20 a second).
- A refused SDK call throws `ForgeRefusal`, a RuntimeException you may catch: a limit of the SDK, a bad argument, a deployment that is over. A refused act throws nothing: its `Outcome` says so.

## 3. Reference

```java
interface ForgeModule {
  void start(ModuleContext ctx);
  default void stop(ModuleContext ctx, StopReason why);
}
enum StopReason { EXPIRED, STOPPED, ENDED, FORGE_OFF, SERVER_STOPPING }
@interface ModuleInfo { String name(); String summary(); }
@interface Param {
  String name();
  ParamType type();
  String doc();
  boolean required() default true;
  String fallback() default "";
  double min() default Double.NEGATIVE_INFINITY;
  double max() default Double.POSITIVE_INFINITY;
  String[] choices() default {};
}
@interface ParamList { Param[] value(); }  // the compiler's, never yours
enum ParamType { PLAYER, PLAYERS, INTEGER, NUMBER, TEXT, BOOLEAN, PLACE }

interface ModuleContext {
  String id();  // its deployment, C-70
  boolean restarted();
  long now();  // epoch ms
  long endsAt();
  Random random();  // its own, seeded per deployment
  Args args(); Events events(); Timers timers(); World world(); Acts acts();
  Creatures creatures(); Ground ground(); Storage storage();
  Game game();  // the judged challenge it referees, or null
  void log(String line);  // the server log, English: 200 chars, 20 a minute
  void tellDirector(String note);  // wakes the Director: French, 200 chars; one per 5 minutes, a sooner one waits
  void end(String why);  // ends it once this call returns, stop(ENDED); why: French, 200 chars, told to the Director
}
interface Args {  // its params as checked; another name or type: ForgeRefusal
  String player(String name);  // online or not; null when left out
  List<String> players(String name);  // empty when left out
  int integer(String name);
  double number(String name);  // an INTEGER reads too
  String text(String name);  // null when left out
  boolean bool(String name);
  Place place(String name);  // null when left out
}
interface Events {
  <F extends GameFact> Subscription on(Class<F> type, Consumer<F> handler);  // a record of GameFact; 16 at most
}
interface Subscription { void cancel(); boolean active(); }
interface Timers {  // in ticks, 1 to 72,000; each run a call of its own
  Scheduled after(int ticks, Runnable task);
  Scheduled every(int ticks, Runnable task);  // first run after ticks; 16 repeating, 64 waiting in all
}
interface Scheduled { void cancel(); boolean active(); }
interface World {  // immediate, loaded chunks only
  List<PlayerInfo> players();  // online, no spectators, in join order
  PlayerInfo player(String name);  // null: not online
  List<CreatureInfo> creatures(Place around, double radius, String type);  // radius up to 48, the 32 nearest; type null: any
  BlockInfo block(Cell cell);  // null: not loaded
  int surface(String dimension, int x, int z);  // the y one stands at on top; Integer.MIN_VALUE: not loaded
  String biome(Place at);  // null: not loaded
  int count(String player, String item);  // items (an id or a #tag) they carry; -1: not online
  long dayTime();  // 0 dawn, 6,000 noon, 13,000 nightfall, 18,000 midnight
  String weather();  // clear, rain or thunder
}
interface Acts {  // §4
  Pending say(String text);  // to everyone: 300 chars, 3 lines
  Pending sayTo(String player, String text);  // only they see it
  Pending title(String player, String title, String subtitle);  // 40 and 80 chars; null player: everyone
  Pending hud(String player, String text);  // the action bar, 80 chars; null: everyone
  Pending sound(String player, String sound);  // entity.chicken.egg; null: everyone
  Pending particles(Place at, String particle, int count);  // 1 to 200
  Pending fireworks(Place at, int count);  // 1 to 8, harmless; from open ground 6 blocks from any player, else failed
  Pending lightning(Place at);  // harmless
  Pending smite(String player, int hearts);  // 1 to 5
  Pending explosion(Place at, double power, boolean hurts);  // 0.5 to 4; never breaks a block
  Pending effect(String player, String effect, int seconds, int level);  // 1 to 900 s, level 1 to 5
  Pending attribute(String player, String attribute, double multiply, int seconds);  // 10 to 900 s, then back
  Pending launch(String player, String direction, double strength);  // up forward backward left right random; 0.5 to 3
  Pending teleport(String player, Place to);  // in their dimension, a safe spot near
  Pending teleportRandom(String player, int radius);  // 10 to 500
  Pending spawn(String type, String near, Spawn how);  // near: an online player; how null: Spawn.of(1)
  Pending punish(String player, String punishment, int severity, String reason);
}
interface Pending { void then(Consumer<Outcome> callback); }  // once; a later tick, a call of its own; never after the end
record Outcome(String status, String text, List<String> creatures) {  // creatures: those spawn summoned, now its own
  boolean ok();
}
record Spawn(int count, int distance, String name, boolean target, int lifetimeMinutes, int height, boolean baby) {
  static Spawn of(int count);  // 1 to 12, capped by chaos (§4); 8 blocks away, no name, no target, 5 minutes, on the ground, adults
  Spawn withDistance(int distance);  // 2 to 24
  Spawn withName(String name);  // 32 chars
  Spawn withTarget(boolean target);  // they go for the player: hostile
  Spawn withLifetimeMinutes(int lifetimeMinutes);  // 1 to 10
  Spawn withHeight(int height);  // 0 to 30 blocks up, no fall damage
  Spawn withBaby(boolean baby);
}
interface Creatures {  // §6
  List<Creature> own();  // alive and loaded
  Creature get(String uuid);  // one of its own, or null
  Creature claim(String uuid);  // null when refused
}
interface Creature {  // steered at once; once not its own, ForgeRefusal but for uuid, info, alive
  String uuid();
  CreatureInfo info();  // null once gone
  boolean alive();
  void moveTo(Place to, double speed);  // walks there, 0.3 to 2.5
  void velocity(double x, double y, double z);  // blocks a tick, each -2 to 2, beyond: ForgeRefusal
  void push(double x, double y, double z);  // adds to its motion; the same bounds
  void lookAt(Place at);
  void gravity(boolean on);  // off: it floats and keeps its motion
  void glow(boolean on);
  void name(String name);  // 32 chars; null: none
  void effect(String effect, int seconds, int level);
  void attribute(String attribute, double multiply);
  Pending attack(String player);  // an act, hostile
  void calm();  // forgets its target
  void release();  // summoned: vanishes; claimed: back to normal, where it was claimed
}
interface Ground {
  Pending place(List<Cell> cells, String block, int seconds);  // an act: 1 to 64 loaded air cells, all or nothing, 1 to 120 s
}
interface Storage {  // texts kept with the world (MAX_STORAGE limits); a long as text: Long.toString, Long.parseLong
  String get(String key);  // null: none
  void put(String key, String value);  // null removes
  void remove(String key);
  Set<String> keys();
  int getInt(String key, int fallback);
  void putInt(String key, int value);
  double getDouble(String key, double fallback);
  void putDouble(String key, double value);
}
interface Game {  // the judged challenge it referees
  String id();  // C-75
  List<String> players();  // its participants
  boolean running();  // true until its verdict, past its deadline too
  Pending win(List<String> winners, String note);  // among players(); note: French, how they won; once
  Pending lose(String note);  // nobody won
}
record Place(String dimension, double x, double y, double z) {  // x east, y up, z south; overworld, the_nether, the_end
  double distance(Place other);  // POSITIVE_INFINITY across dimensions
  Place offset(double dx, double dy, double dz);
  Cell cell();
}
record Cell(String dimension, int x, int y, int z) {
  Place center();
  Cell offset(int dx, int dy, int dz);
}
// yaw 0 south, 90 west, 180 north, -90 east; pitch -90 up, 90 down; gameMode survival, creative or adventure;
// heldItem air when empty; underground: in a cave or a mine
record PlayerInfo(String name, String uuid, Place at, float health, float maxHealth, int food, String gameMode, boolean onGround, boolean sneaking, boolean sprinting, boolean swimming, boolean gliding, float yaw, float pitch, String heldItem, boolean underground) {}
record CreatureInfo(String uuid, String type, Place at, float health, float maxHealth, String name, boolean own) {}
record BlockInfo(Cell cell, String block, boolean air, boolean liquid, boolean solid, boolean placedByPlayer) {}
record Who(String type, String uuid, String name) {  // in facts; type: player, or the creature's type
  boolean isPlayer();
}
sealed interface GameFact {  // §5
  String player();  // whom it is about; null only for CreatureGone
  Place at();  // null when the game did not say
  record BlockBroken(String player, Place at, String block, boolean builtByAnother) {}  // another player had placed it
  record BlockPlaced(String player, Place at, String block) {}
  record CreatureHit(String player, Place at, Who creature, float damage, float health) {}  // never the killing blow
  record PlayerHit(String player, Place at, String victim, float damage) {}
  record PlayerHurt(String player, Place at, String cause, Who attacker, float damage) {}  // 5 s of hits summed, 5 s late
  record CreatureKilled(String player, Place at, Who creature) {}
  record ProjectileHit(String player, Place at, String projectile, Who shooter, Who target) {}  // target null: a block
  record PlayerDied(String player, Place at, String cause, Who killer) {}
  record Chat(String player, Place at, String text) {}
  record DimensionChanged(String player, Place at, String from, String to) {}
  record StructureEntered(String player, Place at, String structure) {}
  record BiomeEntered(String player, Place at, String biome) {}
  record Advancement(String player, Place at, String advancement) {}
  record Crafted(String player, Place at, String item, int count) {}
  record Consumed(String player, Place at, String item) {}
  record Slept(String player, Place at) {}
  record Joined(String player, Place at) {}
  record Left(String player, Place at) {}
  record CreatureAppeared(String player, Place at, Who creature) {}  // a wild one within 64 blocks of player
  record CreatureGone(String player, Place at, Who creature, String why) {}  // its own: died, vanished, unloaded
}
final class Text {  // without regular expressions
  static List<String> words(String text);  // « L'Œuf, déjà ! » → [l, oeuf, deja]
  static String normalize(String text);  // lower case, no accents, all but letters and digits as spaces, folded
  static String cut(String text, int max);  // with … when cut
  static String duration(long millis);  // 36s, 1m05s, 1h12m
}
final class ForgeRefusal extends RuntimeException {
  ForgeRefusal(String message);
}
final class Sdk { int VERSION = 1; }
final class Limits {  // constants: Limits.TICKS_PER_SECOND
  int TICKS_PER_SECOND = 20, MAX_CALL_MILLIS = 20, MAX_DEPTH = 64;
  int MAX_ELEMENTS = 10_000, MAX_CHARS = 65_536, MAX_ARRAY = 65_536;
  int MAX_SUBSCRIPTIONS = 16, MAX_REPEATING_TIMERS = 16, MAX_PENDING_TIMERS = 64, MAX_DELAY_TICKS = 72_000;
  int MAX_ACTS_PER_CALL = 4, MAX_ACTS_PER_MINUTE = 30, MAX_LINES_PER_MINUTE = 4, MAX_HUD_PER_SECOND = 1;
  int MAX_OWN_CREATURES = 32, MAX_CREATURES_RADIUS = 48, MAX_CREATURES_FOUND = 32, MAX_CLAIM_DISTANCE = 32;
  int MAX_LOG_LINES_PER_MINUTE = 20, MAX_NOTE_CHARS = 200, DIRECTOR_NOTE_GAP_MINUTES = 5;
  int MAX_STORAGE_KEYS = 64, MAX_STORAGE_KEY_CHARS = 40, MAX_STORAGE_VALUE_CHARS = 512, MAX_STORAGE_CHARS = 8_192;
  int MAX_PLACE_CELLS = 64, MAX_PLACE_SECONDS = 120;
}
```

## 4. Acts

Each act calls one of the Narrator's tools, checked as for the Director; the acts of one call of the module are one turn for the guards (a protected player, one already punished in it, one dead minutes ago or in grace, the same punishment too soon, the chaos level: refused). 4 acts a call (refused ones count), 30 a minute; `say`, `sayTo`, `title`: 4 a minute, within the server's speech limit shared with the Director; `hud`: once a second per player. Beyond: `DENIED (module_acts): …`, nothing done.

`Outcome.status`: `ok`; `invalid` (a bad argument: a player not online, an unknown id); `denied` (a guard or a limit); `failed` (not possible now); `unknown` (no answer in time: maybe done); `dropped` (the deployment ended first). `text` is the tool's answer (`DENIED (protected): …`). Test `ok()`, not a status. Never insist on the same player: in the callback (a new call), try another one, or give up and say something else.

- The tools, as `refuseNext` names them: `say` (`say`, `sayTo`), `show_title`, `hud`, `play_sound`, `particles`, `fireworks`, `lightning` (`lightning`, `smite`), `explosion`, `apply_effect`, `set_attribute`, `launch`, `teleport` (both), `spawn`, `punish`, `place_blocks` (`ground().place`), `creature_attack` (`Creature.attack`), `game` (`win`, `lose`).
- Hostile, refused like a punishment: `smite`, `launch`, `punish`, `teleportRandom`, `attack`; an `explosion` that hurts; a harmful `effect`; an `attribute` against them; a `teleport` beyond 32 blocks; a `spawn` of hostile creatures near a player or with a target; a `place` within 2 blocks of a player.
- `effect`: never `wither`, `poison`, `instant_damage`, `levitation` (`DENIED (forbidden_effect)`). `teleport`: never to another player nor another dimension. `spawn`: at chaos 0 to 5, 0, 2, 4, 6, 10, 12 a call and 0, 4, 8, 16, 24, 32 of the Narrator's creatures alive (`DENIED (mob_cap)`): summon two by two, handle refusals; never a boss or a creature out of scale (warden, wither, ender dragon…), a `creeper` or a `breeze` (`DENIED (forbidden_creature)`).
- `punish`: `tiny`, `moon_gravity`, `slow_hands`, `heavy_feet`, `hunger`, `butterfingers`, `guests`, `slime_rain`, `skyward`, `tree_top`, `smite`, `cage`, `web`; severity 1 to 5, capped by the chaos level (4 at chaos 3); one per call and player; `reason`: one factual line, French, third person (« a bougé quand le Narrateur regardait »).
- `particles`: `heart`, `flame`, `smoke`, `end_rod`, `happy_villager`… (`game_ids`). `ground().place`: `glass`, a stained glass (`red_stained_glass`), `cobweb`, `slime_block`, `honey_block`; they go by themselves.
- No act gives or takes an item, runs a command, changes the weather, the time or a block (but in thin air), or makes players fight.

## 5. Facts

`ctx.events().on(GameFact.Chat.class, chat -> …)`: each fact of that record, the tick after it happened, in order, each a call of its own. Facts about every player come: filter on yours. 256 a tick, 3,000 a minute; beyond, dropped. Never a prayer, a whisper or a private message. Subscribe to the records, not to `GameFact`.

## 6. Creatures

- Its own: those `spawn` summoned (`Outcome.creatures()`, then `ctx.creatures().get(uuid)`) and those it claimed; 32 at most. They drop nothing, break nothing, attack only players, take no fall damage, never push players. A summoned one vanishes at the end of its lifetime (summon again to last) or of the deployment; a claimed one goes back. `CreatureGone`: one died, vanished or unloaded.
- `claim(uuid)`: a wild hostile creature (`zombie`, `skeleton`…) spawn could summon, within 32 blocks of an online player; null for an animal, a villager, a golem, a tamed, named or ridden one, a boss, another's.
- Steering is immediate, no act; a bad argument throws `ForgeRefusal`. `effect` as a law's `mob_effect`: never `resistance`, `regeneration`, `invisibility`, `levitation`, `jump_boost` nor one that wounds. `attribute`: `scale` 0.5 to 2, `movement_speed` 0.25 to 3, `max_health` 1 to 5, `attack_damage` 0 to 2, `jump_strength` 0 to 1, `gravity` 1 to 2. To act on a player, a creature `attack`s.

## 7. The sandbox

Allowed: the basics of `java.lang` (of `System`, only `nanoTime`, `currentTimeMillis`, `arraycopy`), the collections and helpers of `java.util`, `java.util.function`, the streams and `Collectors`, `AtomicInteger`, `AtomicLong`, `AtomicBoolean`, `AtomicReference`, `ThreadLocalRandom`, and the dates and durations of `java.time`.

The guard refuses the rest, among it: files, the network, reflection; threads, `Thread.sleep`, executors, `synchronized`, `wait`, `ThreadLocal`, parallel streams; regular expressions (`matches`, `split`, `replaceAll`, `replaceFirst`: use `Text.words`, `Text.normalize`, `contains`, `indexOf`, `replace`); endless streams (`Stream.iterate`, `Stream.generate`, `Random.ints`…); `UUID.randomUUID` (use `ctx.random()`); `catch (Throwable)`, `catch (Error)` (catch a precise exception), try-with-resources; `new int[a][b]` (use `new int[a * b]`); a class of its own that is a `Collection`, a `Map` or a `CharSequence` (keep one in a field); the range views `subSet`, `headSet`, `tailSet`, `subMap`, `headMap`, `tailMap` (use `ceiling`, `floor`, `higher`, `lower`).

Its limits throw `ForgeLimit`, an `Error` that ends the call (`catch (Exception e)` does not stop it): 20 ms of its own work a call (50 for the constructor and `start`); 64 nested calls; 16 MiB allocated a call; 10,000 elements a collection; 65,536 characters a text, elements an array or asked capacity; hashing or comparing a structure larger than that. Memory it keeps: 32 MiB in the game, 4 MiB at the bench.

Its faults, over ten minutes, disable it: two calls stopped by time, depth or allocation; one call still computing when given up (25 ms); three exceptions out of its code (an uncaught `ForgeRefusal` counts); any fault in the constructor or `start`; over 50 ms of its work a second for five seconds; too much memory. Then: no `stop`, its creatures taken back, the Director told the fault and its line, the fault noted on its version (`module_list`). A refusal of the guard is a rule, not an obstacle: change the approach within the SDK, never get round it.

## 8. The bench and forge_test

`forge_test` compiles, guards, reads the form, rewrites, then runs the bench. Its first line says where it stopped (`does not compile: 2 errors (14 ms)`, `compiled (14 ms), refused by the sandbox: 1 problem`, `…; scenarios 1/2`) or `ok: …`. Each problem: `[n] error|refused|fault WordRace.java:line: what`, the line, `hint:` lines. Fix them all, send the whole file again.

The bench is a simulated world, with the game's sandbox and SDK; acts are recorded, never done.
1. **Smoke run**: players Bartok (0.5 64 0.5), Mimosa (6.5 64 0.5), Josiane (0.5 64 6.5), a wild zombie; required parameters get a sample (Bartok; Bartok,Mimosa; the min or 1; the first choice; false; 8 64 0), the others their fallback; constructor, `start`, a sample fact of each record it listens to, 1,200 ticks, `stop(EXPIRED)`. It fails on a fault, a call over 20 ms, 4 MiB kept.
2. **Dry checks**: each distinct act is checked by the real tool at chaos 3, as if the Director asked: an unknown id, a severity of 5… fail the test, with the module's line.
3. **Scenarios**, each on a new bench: no player, air from y = 64 up, stone below, plains, clear, `dayTime` 6,000 plus the ticks; the deployment C-1 lasts 10 minutes; a challenge is C-2. The first false `check`, exception, fault or call over 20 ms fails it; 30 s for the whole bench.

Fidelity: no physics, collisions, chunks nor guards (but `refuseNext`); creatures do not fight back and move straight (`moveTo`: 0.2 × speed block a tick); a spawn's appear the tick after. Every act answers `ok (simulated)`, but a refusal you impose, a player not online (`invalid`), `place` on a cell that is not air (`failed`), `hud` twice a second.

```java
interface Bench {
  void player(String name, Place at);  // online, 20 health and food, survival, on the ground; after start: a Joined fact
  void move(String name, Place to);
  void pose(String name, boolean sneaking, boolean onGround, float pitch);
  void hold(String name, String item);  // main hand
  void give(String name, String item, int count);  // what World.count reads
  void health(String name, float health);
  void leave(String name);  // a Left fact
  void block(Cell cell, String block);
  String creature(String type, Place at);  // a wild one it may claim; its uuid
  void challenge(String... players);  // before start: ctx.game() is C-2
  void start(String... params);  // "players=Bartok,Mimosa", "seconds=30", checked as deploy_module does
  void restart();  // stop(SERVER_STOPPING), a new instance, restarted(), the same storage
  void fact(GameFact fact);  // delivered at the next tick
  void refuseNext(String tool, String text);  // the next act through tool answers text: "DENIED (already_punished): …"
  void run(int ticks);
  List<String> acts();  // each act asked, one line each
  List<CreatureInfo> creatures();  // its own, as the bench moved them
  List<String> notes();  // "log: …" and "director: …" lines
  String ended();  // why it ended; null while it runs
  void check(boolean condition, String message);  // false fails the scenario
}
```

`acts()` lines: `say: Ça commence.`, `title Bartok: Attention / le sol brûle`, `hud everyone: 10 s`, `spawn chicken x6 near Bartok`, `punish Bartok tiny 2`, `game won: Mimosa`, `game lost`…, then ` → denied` (or `invalid`, `failed`, `unknown`, `dropped`) when not `ok`. An act is sent at the next tick, its status known a tick later: run 2 ticks before you check it.

Write two scenarios at least: the normal case, and a refusal of the guards (`refuseNext`) that the module handles.

## 9. What costs tries

- `ctx.end` drops every act asked in the same call, a `win` too (`game won: Mimosa → dropped`): end in the callback of the last act, `last.then(o -> ctx.end(why))` (§10).
- Finish 10 to 15 s before `ctx.endsAt()`: acts asked in `stop`, and a verdict on its way at the expiry, are dropped. Rhythms in ticks (timers count the engine's), durations and deadlines in `ctx.now()`.
- A game speaks through `hud` (80 chars, `Text.cut`; `every(20)` for a display each second): `say` and `title` eat the speech limit the Director shares. Two lines a game: the rule, the result.
- 4 acts a call: with many players, one timer per player.
- `tellDirector` notes, `end` reasons, `win` and `lose` notes: French, factual, third person (« Mimosa a gagné », never « tu », « je » nor « vous »), or the memory refuses them.
- A scenario reads a draw (`ctx.random()`) in `b.acts()` or `b.notes()`, then plays it (§10).
- `ctx.world().player(name)` is null for a player offline; a PLAYER parameter may be offline. SDK lists are unmodifiable. `then` once per act; acts asked in `stop` are dropped.
- The built-in modules (version 0) are tested examples to `module_read`: `gravity_storm` (short: a refused hostile act tried on another player), `hot_cold` (storage, restart, a timer per player, a challenge), `tag` and `simon_says` (games that referee a challenge).

```java
ctx.acts().spawn("cow", "Bartok", Spawn.of(3).withHeight(4).withName("Ballon")).then(o -> {
    for (String uuid : o.creatures()) {
        Creature cow = ctx.creatures().get(uuid);
        if (cow != null) cow.gravity(false);  // it floats; cow.velocity(0, 0.1, 0) lifts it
    }
});
```

## 10. Example

A game that referees a judged challenge, with its two scenarios:

```java
import io.github.clemsix6.narrator2.forge.api.*;
import io.github.clemsix6.narrator2.forge.api.test.Bench;
import java.util.*;

@ModuleInfo(name = "word_race", summary = "The Narrator writes a word backwards in the chat; the first player to write it right wins.")
@Param(name = "players", type = ParamType.PLAYERS, doc = "Who races.", min = 2, max = 8)
@Param(name = "seconds", type = ParamType.INTEGER, doc = "Time to find it.", required = false, fallback = "60", min = 20, max = 300)
public final class WordRace implements ForgeModule {
    private static final List<String> WORDS = List.of("creeper", "obsidienne", "lanterne", "citrouille", "enclume");
    private ModuleContext ctx;
    private List<String> players;
    private String word;
    private boolean over;

    @Override
    public void start(ModuleContext ctx) {
        this.ctx = ctx;
        players = ctx.args().players("players");
        word = WORDS.get(ctx.random().nextInt(WORDS.size()));  // the same draw after a restart: same seed
        String backwards = new StringBuilder(word).reverse().toString().toUpperCase(Locale.ROOT);
        ctx.acts().say("Le premier qui écrit ce mot à l'endroit gagne : " + backwards).then(o -> {
            if (!o.ok()) ctx.end("le mot n'a pas pu être annoncé");  // refused: give up, never insist
        });
        ctx.events().on(GameFact.Chat.class, this::heard);
        long ms = Math.min(ctx.args().integer("seconds") * 1000L, ctx.endsAt() - ctx.now() - 15_000);  // done before the expiry
        ctx.timers().after((int) Math.max(1, ms / 50), this::timeUp);
    }

    private void heard(GameFact.Chat chat) {
        if (over || !players.contains(chat.player()) || !Text.normalize(chat.text()).equals(word)) return;
        over = true;
        String winner = chat.player();
        Pending last = ctx.acts().title(null, winner + " gagne", "le mot était " + word);
        Game game = ctx.game();
        if (game != null && game.running()) last = game.win(List.of(winner), winner + " a écrit « " + word + " » le premier");
        last.then(o -> ctx.end(winner + " a gagné la course au mot"));  // ending now would drop the title and the win
    }

    private void timeUp() {
        if (over) return;
        over = true;
        String why = "personne n'a trouvé « " + word + " »";
        Game game = ctx.game();
        if (game != null && game.running()) game.lose(why).then(o -> ctx.end(why));
        else ctx.end(why);
    }

    public static final class Scenarios {
        public void theFirstRightAnswerWins(Bench b) {
            Place here = new Place("overworld", 0.5, 64, 0.5);
            b.player("Bartok", here);
            b.player("Mimosa", here.offset(4, 0, 0));
            b.challenge("Bartok", "Mimosa");
            b.start("players=Bartok,Mimosa");
            b.run(2);
            String said = b.acts().get(0);  // the draw, read in the acts
            b.check(said.startsWith("say: Le premier"), "the word is announced: " + b.acts());
            String word = new StringBuilder(said.substring(said.lastIndexOf(' ') + 1)).reverse().toString();
            b.fact(new GameFact.Chat("Bartok", here, "patate"));
            b.fact(new GameFact.Chat("Mimosa", here, word + " !"));
            b.run(5);
            b.check(b.acts().contains("game won: Mimosa"), "Mimosa wins: " + b.acts());
            b.check(b.ended() != null, "it ends once the win is paid");
        }

        public void aRefusedAnnouncementEndsIt(Bench b) {
            b.player("Bartok", new Place("overworld", 0.5, 64, 0.5));
            b.player("Mimosa", new Place("overworld", 4.5, 64, 0.5));
            b.refuseNext("say", "DENIED (speech_limit): 8 lines in the last minute");
            b.start("players=Bartok,Mimosa");
            b.run(5);
            b.check(b.acts().get(0).endsWith("→ denied"), "the announcement is refused: " + b.acts());
            b.check(b.ended() != null, "it gives up: " + b.notes());
        }
    }
}
```
