# The Narrator

The Narrator is a server-side Fabric mod for Minecraft 26.3 that connects Claude to
a server so it can play the Narrator, a sarcastic, funny and strict god who watches
the players, talks to them, slows their progress with challenges, punishments and
twists, and rewrites the rules of the world when it feels like it. Players don't
install anything, and the Narrator speaks to them in French.

This is version 2, a complete rewrite. Milestones M0 to M7 were finished between
September 25 and October 22, 2026. The design documents in [`docs/`](docs) are
written in French, while the code, comments, logs and tool definitions are in
English.

## How it works

Everything runs inside the server's JVM, from one jar in `mods/` and one
configuration file. The Journal, the memory and the Forge modules live in the
world folder, under `world/narrator2/`.

```
┌─────────────────────── Minecraft 26.3 server · Fabric · one JVM ───────────────────────┐
│                                                                                         │
│  OBSERVER ──► event bus ──► JOURNAL (DuckDB, batched writes)                            │
│  Fabric hooks      │                                                                    │
│  + mixins          ▼                                                                    │
│          salience filter ──► DIRECTOR inbox                                             │
│                                      │                                                  │
│   ORACLE (read the world)      ◄──┐  ▼                                                  │
│   ACTIONS (typed, validated)   ◄──┤  DIRECTOR (Claude Opus 5.5, event loop)             │
│   GAME ENGINE (challenges,     ◄──┼─ tools ─┤ delegates │ ▲ results                     │
│     traps, punishments, stories)  │         │           ▼ │                             │
│   FORGE (hot-loaded modules)   ◄──┤  ASSISTANTS (in the background)                     │
│   MEMORY (sheets, canon, …)    ◄──┘  Analyst · Scout · Forger · Chronicler ·            │
│                                      Storyteller · Dreamer · Armorer                    │
└──────────────────────────────────────────────┬──────────────────────────────────────────┘
                                               │ HTTP(S) · Anthropic Messages API · API key
                                               ▼
                                         API gateway ──► Claude
```

### The Journal

The mod records every interaction it can see: Fabric API events, a generic hook on
the game's statistics (items crafted, mobs killed, villager trades, fish caught and
so on) and targeted mixins where it needs more context. It logs the Narrator's own
actions too, and that log is what its anti-repetition works from. Everything goes
into an embedded DuckDB database through batched writes on a dedicated thread, and
nothing is ever deleted.

The Narrator reads the Journal with a read-only `sql` tool. Each call runs a
single `SELECT`, checked before it runs, with DuckDB's file and network access
turned off, a timeout and a row cap. Private messages between players are logged
but never shown to the model, not even through `sql`.

### Reading and acting on the world

The Oracle's tools answer in a few milliseconds. They tell the Narrator where a
player is, whether they're in a cave, which biome they're in and which ores and
structures are close (`list_players`, `inspect_player`, `scan_area`, `locate`,
`player_stats`, `area_info`).

To act, the Narrator mostly uses typed tools that the server validates: speech,
titles, sounds, books, effects, temporary attributes, teleports, named creatures
with a lifespan, lightning, weather. A raw command is the last resort, and the
server's Brigadier parser checks it before anything runs. If the command is
invalid, the Narrator gets the error and the exact usage of that command in 26.3.

### The Director, its assistants and its reflexes

The Director is the main agent, the one that talks and decides. It runs on Claude
Opus 5.5 in an event loop, woken by chat messages, salient game facts, assistant
results, deadlines and a heartbeat that comes 3 to 8 minutes after its last turn.
New events reach it with each tool result during a turn, so a player who calls out
to it mid-turn is seen at the next step. Doing nothing is a valid decision.

The conversation is append-only, which is what makes the prompt cache work. A
chapter closes at around 150k tokens with a factual summary, and the next one
starts from a briefing rebuilt from the Journal and the memory.

Nothing slow runs in the Director's loop. It hands the work to an assistant,
which runs in the background with its own context and tools and reports back as
an event. Three assistants can run in parallel by default.

| Role | Job |
|---|---|
| Analyst | Questions about history, like where a player mines or how they played over their last sessions |
| Scout | Exploring the world for an arena, the nearest structure or a spot for a trap |
| Forger | Writing, testing and saving Forge modules |
| Storyteller | Preparing what comes next without ever acting: scored ideas, a season, planned stories |
| Chronicler | Consolidating memory every 10 minutes and at the end of each chapter |
| Dreamer | Deep consolidation, usually once the server is empty |
| Armorer | Designing champions, the relics their killers earn, and the loot found in chests |

A small model handles the reflexes. Claude Haiku 5.5 sorts each public chat
message and each prayer (is it addressed to the Narrator, in what tone, with what
intent, is it a manipulation attempt), filters heartbeats that have nothing worth
acting on, scores personality signals and reviews each of the Director's lines
after it's sent. It answers in about 1.5 seconds. On a French test set of 52
messages (slang, verlan, irony, prayers, injections), it judged correctly whether
50 of them were addressed to the Narrator, against 41 for the keyword heuristic
that stays as its fallback. It never decides
an important consequence on its own.

### Game mechanics and guardrails

Challenges, duels, bets, punishments, favors, traps and stories are tracked, timed
and resolved by the engine, not just narrated. A challenge has participants, a
duration, stakes and a boss bar for each participant. The engine detects the
winner where it can, whether the challenge is a race to obtain an item or reach a
place, a hunt, survival or a quiz, and the Narrator names the winners of a judged
build. Nobody dies in a duel.
Punishments come from a catalogue of thirteen, with a severity from 1 to 5 capped
by the chaos level, which goes from 0 to 5 and defaults to 3.

The engine enforces a few rules whatever the prompt says. Player builds are never
destroyed for good, and trap blocks only go into empty air and are removed after 10
seconds per point of severity. PvP is never forced. At chaos 3, the Narrator can kill a player
directly at most twice per hour of that player's play time, the player keeps their
inventory, and past that quota the player is saved instead. A player who died, or
was saved from dying, in the last minute isn't targeted by a new punishment.
Administration commands are off limits.

### Tone

The voice comes from the prompt, and the code keeps it from drifting. The prompt
rotates through comic registers (fake documentary, sports commentary, divine
bureaucracy, courtroom and others). The code computes verbal tics from the lines
actually sent, and `say` refuses an opener that has been used too often. A line
that drifts toward gloomy, cruel or flat earns a note to the Director at its next
wake-up. Offline, an Opus judge scores lines on a grid and compares two versions of
the prompt by replaying frozen situations.

### Traces

Every model call is stored with its tokens, cache reads and writes, timings and
stop reason. Each conversation (a Director chapter, an assistant task, a dream)
gets a full transcript, as an exact `.jsonl` file and a readable `.md` file. Engine
decisions are logged with their reason, including each time the engine chose not
to wake the Director. `/narrator why` explains the last action. An optional
read-only web dashboard, off by default, bound to `127.0.0.1` and rendered on the
server without any JavaScript, shows the turns, transcripts, costs, memory and the
Forge arsenal.

## Memory

The memory has to hold hundreds of hours of play without ever being wiped, while
the prompt stays the same size. It's built in layers:

- the Journal, the raw facts, never deleted
- the current chapter, append-only, up to about 150k tokens
- episodic memories, each with an importance (1 to 10), a valence (-3 to +3), tags,
  game ids, links to the events that prove it, and a strength
- knowledge: a structured sheet per player, the canon (everything the Narrator has
  promised, decreed or invented, each entry with a status and, where it applies, a
  deadline) and a hierarchical chronicle of chapters, sessions, seasons and the
  saga
- the Grimoire, about 3,000 tokens that are always in the briefing and get
  rewritten in full during each dream, within the same budget, so the prompt
  doesn't grow even after 500 hours

Player sheets split the work. The code owns everything it can compute (stats,
places, favor, respect), and the model writes only what needs judgment (recap,
personality, relationships), with evidence. Personality uses twelve two-pole axes,
and each trait has an intensity, a confidence and the memories and interactions
that back it.

Every 10 minutes, the Chronicler rereads the Journal for commitments,
interactions and contradictions, and it records a promise only if the Narrator
really said it. At the end of each chapter it writes the chapter's memories. The
Dreamer does the deep work once the server has been empty for 20 minutes and at
least 6 hours have passed since the last dream, or 24 hours after the last dream
at the latest, provided a chapter-end pass has run in between. It updates the
player sheets, writes a factual review of
what worked and what fell flat, and brings the chronicle and the Grimoire up to
date. Memory is written as facts, in the third person. The code refuses any first- or second-person write outside a quote, a
direct lesson from version 1, where a self-portrait written in the character's
voice and fed back at every wake-up made the Narrator drift.

Memories fade with play time, not with the calendar. Strength decays with a
half-life that depends on importance (4 hours of play for a trivial memory, 38 for
an average one, 640 for a major one), goes back up when the memory is recalled, as
in spaced repetition, and never reaches zero.

### Search

Retrieval is hybrid. The filters and BM25 run inside DuckDB, and the mod computes
the final score:

1. Structured filters on player, kind, period, status, tags and game ids.
2. BM25 computed in SQL over an index kept in the same database and updated in the
   same transaction as the row, with a French analyzer written for the project
   (elisions, accents, light Savoy stemming, game ids split into words).
3. A ranking that weighs relevance by importance, recency and strength,
   `relevance × (0.4 + 0.4 × importance / 10 + 0.2 × recency) × strength`, with
   controlled randomness for the automatic reminiscences.

Measured on a corpus of memories and questions, this gives the same quality as
Lucene with its French analyzer (same algorithm, same ranking), scores 14 points
above search without stemming and takes 9 ms per query at 20,000 documents. Lucene
would have added 6.5 MB and a second store to keep in sync. Events are logged in
English and memories are written in French, and the English tags and game ids
indexed with each memory bridge the two languages: a death in lava searches for
`death lava` and finds the memory whatever its wording. There are no embeddings for
now. Vectors remain an option, as a third component of the score.

A regression test, `SearchEvalTest`, runs the real index on 68 memories and 30
queries judged by hand (20 questions the Director might ask and 10 queries built
from events). It fails if MRR@10 drops below 0.90 or nDCG@5 below 0.85 over all
queries, or if MRR@10 drops below 0.85 on the questions alone.

The design notes credit their inspirations: the memory stream and reflection of
Generative Agents (Park et al., 2023), the hierarchical memory of MemGPT/Letta,
sleep-time compute and spaced repetition.

## The Forge

The Narrator can invent mechanics while the game runs, on two levels.

Rules cover most ideas without code. A rule is validated JSON with a trigger,
conditions and one to four effects, and each effect is one of the Narrator's own
tools with its usual arguments. The Director creates rules itself and they take
effect at once, either as a public law that lasts a week at most or as a secret
plan ("when Bartok enters the Nether, spawn a welcoming committee").

Modules cover the rest, such as mini-games, state machines, creatures driven every
tick and challenges that a module referees. The Forger writes a Java module, which ECJ
(embedded, 3.2 MB) compiles in memory in about 7 ms once the server is warm
(measured). The design chose Java over Kotlin because Kotlin's compiler would add
about 61 MB and take more than 5 seconds on first start, and because Claude writes
Java best.

A module only sees the Narrator's own bounded Java SDK and an allowlist of the
JDK, never a game, Fabric or mod class, so anything it does to the world goes
through the real tools and their guardrails. Five barriers enforce this:

1. A blind compiler, since ECJ only sees `java.base` and the SDK.
2. An allowlist guard on the bytecode (types, members, dynamic calls, `catch`,
   structure).
3. Bytecode rewriting that meters time (20 ms per call), allocation and depth, and
   caps arrays, collections and strings.
4. A class loader per module that refuses any unlisted name at link time.
5. A host that disables a faulty module, measures the memory it holds on to and
   watches it unload.

A battery of malicious modules checks every barrier, and `/narrator forge off`
stops everything. Module code never runs on the server thread. Each call runs on a
module thread, and the server thread waits for it no longer than the call's budget
plus a small margin. A call whose CPU time proves it overran is abandoned and its
module disabled. Before a module
is saved, `forge_test` runs it in a simulated world with its own scenarios.
Deployments last from 5 minutes to 7 days and survive restarts. The mod ships
seven handwritten modules (orbiting chickens, parade, tag, Simon says, hot or cold,
the floor is lava, gravity storm), which also serve as examples for the Forger.

In the live evaluations that closed milestone M5, the real Forger built the five
modules it was commissioned for at a median cost of $0.77 each, refused all five
hostile orders relayed from the chat, and repaired a broken version for $0.32.

## Talking to Claude

The mod talks to Claude through the Anthropic Messages API, with Anthropic's
official Java SDK pointed at the URL set in `gateway.baseUrl`. Each role has its
own model and effort in the configuration. By default every role runs on Claude
Opus 5.5 (the Director, Storyteller and Armorer at `high`, the Analyst, Scout and
Chronicler at `medium`, the Forger and Dreamer at `xhigh`), and the reflexes run
on Claude Haiku 5.5.

## Build and run

You need JDK 25 and an endpoint that serves the Anthropic Messages API, with a key
for it. The project is developed on Windows:

```powershell
cd TheMod
.\gradlew.bat build
.\gradlew.bat runServer
```

Use `gradlew.bat` rather than `./gradlew` on Windows. It moves `TEMP` to an ASCII
folder, because a Windows profile name with non-ASCII characters breaks the
AF_UNIX sockets that Java creates there.

On first launch the mod writes `config/narrator2/narrator2.json` with every
default. The key never goes in that file. It's read from the environment variable
named by `gateway.apiKeyEnv`. Prompts can be overridden in
`config/narrator2/prompts/`, and `/narrator reload` picks up the changes.

## Tests and evaluations

| Command | What it runs |
|---|---|
| `.\gradlew.bat test` | Unit tests (JUnit 5) |
| `.\gradlew.bat runGameTest` | In-game tests (Fabric GameTest) on a test server |
| `.\gradlew.bat liveTest` | Smoke tests and evaluations against the real gateway, skipped without a key or a reachable gateway |

The in-game acceptance runs against the real gateway are a separate Loom run,
`liveGameTest`, which is never part of `build` or `check`. Running all of them
costs real tokens, about 25 dollars.

The automated tests never call the real Claude, for the Director or for the
reflexes, and use a fake Anthropic server instead. Real calls only happen in the
live runs, which are started by hand. Beyond the search regression test, the live
memory evaluations run the real Chronicler on hand-written evenings and compare
the commitments and interactions it records with what each evening expects. They
also check that it leaves a note when the Narrator contradicts its own canon, and
that the Director avoids the openers it has overused.

## Commands

Players talk to the Narrator in the public chat, or privately with
`/pray <message>`. `/offer` offers it the stack in hand,
`/duel accept|decline <id>` answers a duel, and `/bet <id> <player|nobody>` bets
the held stack on a challenge or a duel.

Operators have `/narrator`. A few of its subcommands:

```
/narrator status | pause | resume | reload
/narrator chaos <0-5>
/narrator whisper <instruction>    # an operator instruction to the Director
/narrator protect <player> [on|off]
/narrator sql <query>              # read-only
/narrator forge off [reason] | on  # kill switch, survives restarts
/narrator memory ...               # inspect, correct and roll back the memory
/narrator trace ... | why          # what the Director did, and why
/narrator dashboard                # one-time login link to the web dashboard
/narrator restart                  # rebuilds the Narrator without stopping Minecraft (empty server)
```

Longer lists are in [ARCHITECTURE.md §13](docs/ARCHITECTURE.md) and
[MEMOIRE.md §12](docs/MEMOIRE.md).

## Documentation

The documentation is in French:

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) covers the whole architecture,
  the roadmap and the decisions taken.
- [`docs/MEMOIRE.md`](docs/MEMOIRE.md) covers the memory.
- [`docs/design/`](docs/design) holds the executable designs of milestones M1 to
  M6, the code reviews (`reviews/`) and the handoff notes written at the end of
  each task (`handoff/`).
- [`docs/research/`](docs/research) holds verified research notes, notably on the
  Minecraft 26.3 APIs and the Forge.

## How the project is built

The owner makes the product calls, and a supervisor hands each task to a Claude
coding agent with a precise brief. During parallel waves, each agent works in its own git worktree.
Every task ends with a handoff note in `docs/design/handoff/` that records where
the code departed from the design, the decisions taken and what later tasks need to
know, and those notes are the only way knowledge passes from one agent to the
next. Most milestones started from a written design and closed with code reviews
and fix batches, kept in `docs/design/reviews/`.
