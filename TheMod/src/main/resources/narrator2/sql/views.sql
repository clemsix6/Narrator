-- Views and macros for the model (M1_M2 §6.4). Run by DuckDatabase at every opening, after the migrations:
-- CREATE OR REPLACE keeps them in step with the code. Views and table macros are what the model queries most,
-- so they are shaped for short queries: every one has a `player` column (the name) or a player parameter,
-- explicit column names, and ids without the minecraft: namespace (diamond_ore rather than minecraft:diamond_ore).
-- Views of facts start with the fact's id: several facts share a millisecond, and ORDER BY ts, id keeps their order.
-- Comments are read back by the schema description given to the model (SchemaDoc): keep them short, in English.
--
-- Privacy: nothing here may read a table of Journal.HIDDEN_FROM_MODEL (private_messages). SqlRunnerTest checks
-- every view and macro against it; columns_of() is the one place that names it, to leave it out.

-- ── Scalar macros ───────────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE MACRO bare(id) AS regexp_replace(id, '^minecraft:', '');
COMMENT ON MACRO bare IS 'an id without the minecraft: namespace: bare(''minecraft:stone'') = ''stone''';

CREATE OR REPLACE MACRO ago(t) AS (
    CASE
        WHEN t IS NULL THEN NULL
        WHEN epoch(now() - t) < 60 THEN CAST(greatest(floor(epoch(now() - t)), 0) AS BIGINT) || 's ago'
        WHEN epoch(now() - t) < 3600 THEN CAST(floor(epoch(now() - t) / 60) AS BIGINT) || 'm ago'
        WHEN epoch(now() - t) < 86400 THEN CAST(floor(epoch(now() - t) / 3600) AS BIGINT) || 'h ago'
        ELSE CAST(floor(epoch(now() - t) / 86400) AS BIGINT) || 'd ago'
    END
);
COMMENT ON MACRO ago IS 'how long ago a timestamp was: 42s ago, 5m ago, 3h ago, 4d ago';

-- ── Views ───────────────────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE VIEW timeline AS
SELECT id, ts, player, type AS kind,
       CASE
           WHEN subject IS NULL THEN bare(other)
           WHEN other IS NULL THEN bare(subject)
           ELSE bare(subject) || ' → ' || bare(other)
       END AS what,
       bare(dim) AS dim, x, y, z
FROM events
WHERE type NOT IN ('pickup', 'stat')
UNION ALL
SELECT id, ts, player, 'chat:' || channel, text, NULL, NULL, NULL, NULL
FROM chat;
COMMENT ON VIEW timeline IS 'Events and chat, without item pickups and stats';
COMMENT ON COLUMN timeline.id IS 'event or chat line id, in the order they happened: ORDER BY ts, id';
COMMENT ON COLUMN timeline.player IS 'NULL for the Narrator''s lines (chat:narrator, chat:operator)';
COMMENT ON COLUMN timeline.kind IS 'event type, or chat:<channel> for chat lines';
COMMENT ON COLUMN timeline.what IS 'subject, or subject → other (see the event types); the text for chat';

-- The Narrator is no player: its lines have no player name (a player may well be called NARRATOR).
CREATE OR REPLACE VIEW chat_log AS
SELECT id, ts, channel,
       CASE channel WHEN 'narrator' THEN 'narrator' WHEN 'operator' THEN 'operator' ELSE 'player' END AS speaker,
       player, recipients, text
FROM chat;
COMMENT ON VIEW chat_log IS 'Chat, prayers and the Narrator''s lines (speaker narrator or operator, player NULL)';
COMMENT ON COLUMN chat_log.channel IS 'public, pray (to the Narrator), narrator, or operator (a line an operator made the Narrator say)';
COMMENT ON COLUMN chat_log.speaker IS 'player, narrator, or operator (the Narrator, made to speak by an operator)';
COMMENT ON COLUMN chat_log.player IS 'the player who wrote; NULL for the Narrator';
COMMENT ON COLUMN chat_log.recipients IS 'names of the recipients; NULL = everyone';

CREATE OR REPLACE VIEW narrator_actions AS
SELECT id, ts, tool, targets, args, status, result, turn_id
FROM tool_calls
WHERE effect IN ('speech', 'world');
COMMENT ON VIEW narrator_actions IS 'What the Narrator already did: its speech and world tool calls';
COMMENT ON COLUMN narrator_actions.status IS 'ok, invalid, denied, failed, timeout or unknown';

CREATE OR REPLACE VIEW mining AS
SELECT id, ts, player, bare(subject) AS block,
       ends_with(subject, '_ore') OR subject = 'minecraft:ancient_debris' AS ore,
       y, bare(dim) AS dim, x, z, bare(other) AS tool
FROM events
WHERE type = 'block_break';
COMMENT ON VIEW mining IS 'Blocks broken';
COMMENT ON COLUMN mining.ore IS 'true for ores and ancient debris';
COMMENT ON COLUMN mining.tool IS 'item in the main hand; NULL: bare hand';

CREATE OR REPLACE VIEW building AS
SELECT id, ts, player, bare(subject) AS block, bare(dim) AS dim, x, y, z
FROM events
WHERE type = 'block_place';
COMMENT ON VIEW building IS 'Blocks placed';

CREATE OR REPLACE VIEW deaths AS
SELECT id, ts, player, bare(subject) AS cause, bare(other) AS killer, detail ->> 'message' AS message,
       coalesce(CAST(detail ->> 'narrator' AS BOOLEAN), false) AS narrator,
       bare(dim) AS dim, x, y, z
FROM events
WHERE type = 'death';
COMMENT ON VIEW deaths IS 'Player deaths';
COMMENT ON COLUMN deaths.cause IS 'damage type';
COMMENT ON COLUMN deaths.killer IS 'player name or entity type; NULL: none';
COMMENT ON COLUMN deaths.narrator IS 'caused by the Narrator';

CREATE OR REPLACE VIEW kills AS
SELECT id, ts, player, bare(subject) AS victim, detail ->> 'victim_name' AS victim_name, bare(other) AS weapon,
       coalesce(CAST(detail ->> 'pvp' AS BOOLEAN), false) AS pvp,
       coalesce(CAST(detail ->> 'tamed' AS BOOLEAN), false) AS tamed,
       bare(dim) AS dim, x, y, z
FROM events
WHERE type = 'kill';
COMMENT ON VIEW kills IS 'Entities killed by players';
COMMENT ON COLUMN kills.victim IS 'entity type (player for a player)';
COMMENT ON COLUMN kills.victim_name IS 'the victim''s name: player name or custom name';

CREATE OR REPLACE VIEW damage AS
SELECT id, ts, player, 'taken' AS direction, bare(subject) AS damage_type, amount, bare(other) AS other
FROM events
WHERE type = 'damage_taken'
UNION ALL
SELECT id, ts, player, 'dealt', bare(detail ->> 'damage_type'), amount, bare(subject)
FROM events
WHERE type = 'damage_dealt';
COMMENT ON VIEW damage IS 'Damage players took (hits within 5 s merged) and dealt without killing';
COMMENT ON COLUMN damage.direction IS 'taken or dealt';
COMMENT ON COLUMN damage.other IS 'the attacker (taken) or the victim (dealt)';

CREATE OR REPLACE VIEW trades AS
SELECT id, ts, player, bare(subject) AS item, CAST(amount AS INTEGER) AS count,
       (detail -> 'cost_a' ->> 'count') || ' ' || bare(detail -> 'cost_a' ->> 'item') AS cost_a,
       (detail -> 'cost_b' ->> 'count') || ' ' || bare(detail -> 'cost_b' ->> 'item') AS cost_b,
       bare(other) AS profession
FROM events
WHERE type = 'trade';
COMMENT ON VIEW trades IS 'Trades with villagers';
COMMENT ON COLUMN trades.cost_a IS 'first payment, e.g. 24 paper';

CREATE OR REPLACE VIEW meals AS
SELECT id, ts, player, bare(subject) AS item, CAST(detail ->> 'nutrition' AS INTEGER) AS nutrition
FROM events
WHERE type = 'consume';
COMMENT ON VIEW meals IS 'What players ate or drank';

CREATE OR REPLACE VIEW crafts AS
SELECT id, ts, player, bare(subject) AS item, CAST(amount AS INTEGER) AS count, bare(other) AS station
FROM events
WHERE type = 'craft';
COMMENT ON VIEW crafts IS 'Items crafted or smelted';

CREATE OR REPLACE VIEW advancements AS
SELECT id, ts, player, bare(subject) AS advancement, detail ->> 'title' AS title, other AS frame
FROM events
WHERE type = 'advancement';
COMMENT ON VIEW advancements IS 'Advancements earned';
COMMENT ON COLUMN advancements.frame IS 'task, goal or challenge';

CREATE OR REPLACE VIEW visits AS
SELECT id, ts, player, CASE type WHEN 'biome_enter' THEN 'biome' ELSE 'structure' END AS kind, bare(subject) AS place,
       coalesce(CAST(detail ->> 'first_visit' AS BOOLEAN), false) AS first_visit,
       bare(dim) AS dim, x, y, z
FROM events
WHERE type IN ('biome_enter', 'structure_enter');
COMMENT ON VIEW visits IS 'Biomes and structures entered';
COMMENT ON COLUMN visits.kind IS 'biome or structure';

CREATE OR REPLACE VIEW travels AS
SELECT id, ts, player, CASE type WHEN 'teleport' THEN 'teleport' ELSE detail ->> 'via' END AS via,
       bare(detail -> 'from' ->> 'dim') AS from_dim, CAST(detail -> 'from' ->> 'x' AS INTEGER) AS from_x,
       CAST(detail -> 'from' ->> 'y' AS INTEGER) AS from_y, CAST(detail -> 'from' ->> 'z' AS INTEGER) AS from_z,
       bare(dim) AS dim, x, y, z,
       CASE type WHEN 'teleport' THEN round(amount) END AS distance
FROM events
WHERE type IN ('dimension_change', 'teleport');
COMMENT ON VIEW travels IS 'Dimension changes and teleports (jumps of 64+ blocks)';
COMMENT ON COLUMN travels.via IS 'nether_portal, end_portal, end_gateway, end_exit, other (a command), or teleport';

CREATE OR REPLACE VIEW griefs AS
SELECT e.id, e.ts, e.player, bare(e.subject) AS block, coalesce(p.name, e.detail ->> 'placed_by') AS owner,
       bare(e.dim) AS dim, e.x, e.y, e.z
FROM events e
LEFT JOIN players p ON p.player_uuid = TRY_CAST(e.detail ->> 'placed_by' AS UUID)
WHERE e.type = 'block_break'
  AND (e.detail ->> 'placed_by') IS NOT NULL
  AND TRY_CAST(e.detail ->> 'placed_by' AS UUID) IS DISTINCT FROM e.player_uuid;
COMMENT ON VIEW griefs IS 'Blocks broken that another player had placed';
COMMENT ON COLUMN griefs.owner IS 'the player who had placed the block';

CREATE OR REPLACE VIEW play_sessions AS
SELECT player, joined_at, left_at,
       round(coalesce(duration_s, epoch(now() - joined_at)) / 60, 1) AS minutes,
       left_at IS NULL AS online, end_reason,
       bare(join_dim) AS join_dim, join_x, join_y, join_z,
       bare(leave_dim) AS leave_dim, leave_x, leave_y, leave_z
FROM sessions;
COMMENT ON VIEW play_sessions IS 'Connections, from join to leave; online = still connected';
COMMENT ON COLUMN play_sessions.end_reason IS 'leave, server_stop, or recovered (closed after a crash)';

CREATE OR REPLACE VIEW playtime AS
SELECT CAST(joined_at AS DATE) AS day, player, count(*) AS sessions,
       round(sum(coalesce(duration_s, epoch(now() - joined_at))) / 3600, 2) AS hours
FROM sessions
GROUP BY ALL;
COMMENT ON VIEW playtime IS 'Play time per player and day (the day a session started)';

CREATE OR REPLACE VIEW player_stats AS
SELECT p.name AS player, regexp_replace(s.stat, 'minecraft[.:]', '', 'g') AS stat, s.value, s.updated_at
FROM stat_totals s
JOIN players p USING (player_uuid);
COMMENT ON VIEW player_stats IS 'Vanilla stat totals, e.g. mined:stone, killed:zombie, custom:play_time (ticks)';

-- ── Table macros ────────────────────────────────────────────────────────────────────────────────────
-- Player names are compared without case.

CREATE OR REPLACE MACRO recent(p, minutes := 30) AS TABLE
SELECT ts, kind, what, dim, x, y, z
FROM timeline
WHERE lower(player) = lower(p) AND ts >= now() - to_minutes(CAST(minutes AS BIGINT))
ORDER BY ts, id;
COMMENT ON MACRO TABLE recent IS 'the timeline of a player over the last minutes (default 30)';

CREATE OR REPLACE MACRO hotspots(p, t := 'block_break', cell := 32) AS TABLE
SELECT dim, cx * cell + cell // 2 AS x, cz * cell + cell // 2 AS z, count(*) AS n, min(y) AS y_min, max(y) AS y_max,
       max(ts) AS last
FROM (
    SELECT bare(dim) AS dim, CAST(floor(x / cell) AS BIGINT) AS cx, CAST(floor(z / cell) AS BIGINT) AS cz, y, ts
    FROM events
    WHERE lower(player) = lower(p) AND type = t AND x IS NOT NULL
)
GROUP BY dim, cx, cz
ORDER BY n DESC, last DESC;
COMMENT ON MACRO TABLE hotspots IS 'where a player does most of an event type (default block_break): cells of cell blocks (default 32), x, z = center, busiest first';

CREATE OR REPLACE MACRO kill_stats(p, since := NULL) AS TABLE
SELECT bare(subject) AS victim, count(*) AS kills, bool_or(coalesce(CAST(detail ->> 'pvp' AS BOOLEAN), false)) AS pvp,
       max(ts) AS last
FROM events
WHERE type = 'kill' AND lower(player) = lower(p) AND (since IS NULL OR ts >= since)
GROUP BY ALL
ORDER BY kills DESC, victim;
COMMENT ON MACRO TABLE kill_stats IS 'what a player killed, by entity type, since a time (default NULL: ever)';

CREATE OR REPLACE MACRO sleep_spots(p) AS TABLE
SELECT *
FROM (
    SELECT 'bed' AS kind, bare(dim) AS dim, x, y, z, count(*) AS times, max(ts) AS last
    FROM events
    WHERE type = 'sleep_start' AND lower(player) = lower(p)
    GROUP BY ALL
    UNION ALL
    SELECT 'respawn_point', bare(detail -> 'point' ->> 'dim'), CAST(detail -> 'point' ->> 'x' AS INTEGER),
           CAST(detail -> 'point' ->> 'y' AS INTEGER), CAST(detail -> 'point' ->> 'z' AS INTEGER), count(*), max(ts)
    FROM events
    WHERE type = 'spawn_set' AND lower(player) = lower(p) AND (detail -> 'point') IS NOT NULL
    GROUP BY ALL
)
ORDER BY last DESC;
COMMENT ON MACRO TABLE sleep_spots IS 'where a player sleeps: beds used and respawn points set, latest first';

CREATE OR REPLACE MACRO trail(p, minutes := 60) AS TABLE
SELECT ts, bare(dim) AS dim, x, y, z, bare(biome) AS biome, bare(structure) AS structure, underground, reason,
       CASE WHEN dim = lag(dim) OVER w THEN CAST(round(sqrt((x - lag(x) OVER w) ^ 2 + (z - lag(z) OVER w) ^ 2)) AS BIGINT) END AS moved
FROM positions
WHERE lower(player) = lower(p) AND ts >= now() - to_minutes(CAST(minutes AS BIGINT))
-- Positions have no id: rowid, the order they were written in, breaks the ties of ts (the table is append only).
WINDOW w AS (ORDER BY ts, rowid)
ORDER BY ts, rowid;
COMMENT ON MACRO TABLE trail IS 'where a player went over the last minutes (default 60): sampled positions, moved = blocks from the previous one';

CREATE OR REPLACE MACRO columns_of(t) AS TABLE
SELECT column_name AS name, data_type AS type, comment
FROM duckdb_columns()
WHERE database_name = current_database() AND schema_name = 'main' AND lower(table_name) = lower(t)
  AND table_name NOT IN ('private_messages') -- Journal.HIDDEN_FROM_MODEL
ORDER BY column_index;
COMMENT ON MACRO TABLE columns_of IS 'the columns of a table or view, with types and comments';

-- ── The memory (M3 §13) ─────────────────────────────────────────────────────────────────────────────
-- What the Narrator remembers, for the Director and the Analyst: the memory's tables stay readable, but only these
-- views are described to the model. They give the references the memory tools take (M-812, C-45, I-513, H-88),
-- players by their current name (a UUID no player row knows stays a UUID), and leave out what is no longer
-- remembered: archived rows (an operator's forget, a faded memory, one merged into another) and superseded chronicle.

CREATE OR REPLACE VIEW mem_memories AS
WITH n AS (SELECT map(list(player_uuid), list(name)) AS names FROM players),
     -- The world play clock, saved every 5 minutes by the memory (DuckPlayClock).
     clock AS (SELECT max(TRY_CAST(value AS BIGINT)) AS world_s FROM memory_state WHERE key = 'world_play_s')
SELECT 'M-' || m.id AS ref, m.kind, m.occurred_from AS occurred, m.title, m.content,
       list_transform(m.players, u -> coalesce(n.names[u], CAST(u AS VARCHAR))) AS players, m.tags, m.importance, m.valence,
       -- Decay.strength: max(0.02, 2^(-age / half-life)), age in hours of world play since the last reinforcement;
       -- NULL while the clock is unknown (greatest() would skip the NULL and say 1). Results show two decimals.
       CASE WHEN clock.world_s IS NOT NULL
            THEN greatest(0.02, pow(2, -greatest(0, clock.world_s - m.reinforced_play_s) / 3600 / m.stability_h))
       END AS strength
FROM memories m, n, clock
WHERE NOT m.archived;
COMMENT ON VIEW mem_memories IS 'Episodes and observations (French); strength: 1 fresh to 0.02 faded';
COMMENT ON COLUMN mem_memories.ref IS 'its reference for the memory tools, e.g. M-812';
COMMENT ON COLUMN mem_memories.kind IS 'episode or observation';
COMMENT ON COLUMN mem_memories.occurred IS 'when it began';
COMMENT ON COLUMN mem_memories.players IS 'who it is about, the main player first; empty: the whole server';
COMMENT ON COLUMN mem_memories.tags IS 'controlled tags: death, lava, insult, promise, nether, gag:slug, arc:C-45…';
COMMENT ON COLUMN mem_memories.importance IS '1 (trivial) to 10 (major)';
COMMENT ON COLUMN mem_memories.valence IS '-3 to +3, for the main player';
COMMENT ON COLUMN mem_memories.strength IS 'how well it is remembered: 1 fresh, down to 0.02 faded; recalling it makes it stronger';

CREATE OR REPLACE VIEW mem_canon AS
WITH n AS (SELECT map(list(player_uuid), list(name)) AS names FROM players)
SELECT 'C-' || c.id AS ref, c.kind, c.mechanic, c.status, c.statement, c.quote,
       list_transform(c.players, u -> coalesce(n.names[u], CAST(u AS VARCHAR))) AS players,
       c.due_at, c.resolved_at, c.resolution, c.created_at
FROM canon c, n
WHERE NOT c.archived;
COMMENT ON VIEW mem_canon IS 'What the Narrator said or decided: promises, challenges, laws, nicknames, places, lore…; open: status active or planned';
COMMENT ON COLUMN mem_canon.ref IS 'its reference for the memory tools, e.g. C-45';
COMMENT ON COLUMN mem_canon.kind IS 'promise, threat, challenge, law, plan, prophecy, nickname, title, place, lore, opinion, running_gag, arc or claim';
COMMENT ON COLUMN mem_canon.mechanic IS 'challenge, duel or rule: the mechanics engine tracks it and ends it (engine_decisions kind mechanic); NULL: not the engine''s';
COMMENT ON COLUMN mem_canon.status IS 'planned or active (open); fulfilled, broken, expired, won, lost, abandoned, cancelled, resolved or retired';
COMMENT ON COLUMN mem_canon.statement IS 'what it says, in French';
COMMENT ON COLUMN mem_canon.quote IS 'the Narrator''s own words, when said to players';
COMMENT ON COLUMN mem_canon.players IS 'to or about whom; empty: everyone';
COMMENT ON COLUMN mem_canon.due_at IS 'deadline; NULL: none';
COMMENT ON COLUMN mem_canon.resolution IS 'how it ended';

CREATE OR REPLACE VIEW mem_interactions AS
SELECT 'I-' || i.id AS ref, i.ts, coalesce(p.name, i.player) AS player, i.kind, i.valence, i.disrespect, i.summary, i.quote
FROM interactions i
LEFT JOIN players p USING (player_uuid)
WHERE NOT i.archived;
COMMENT ON VIEW mem_interactions IS 'Noted conduct toward the Narrator (insult, prayer…) and his punishments, blessings, gifts';
COMMENT ON COLUMN mem_interactions.ref IS 'its reference for the memory tools, e.g. I-513';
COMMENT ON COLUMN mem_interactions.player IS 'the player''s current name';
COMMENT ON COLUMN mem_interactions.valence IS 'how it reflects on the player in the Narrator''s eyes, -3 to +3';
COMMENT ON COLUMN mem_interactions.disrespect IS 'a lack of respect toward the Narrator';
COMMENT ON COLUMN mem_interactions.summary IS 'what happened, in French';
COMMENT ON COLUMN mem_interactions.quote IS 'the player''s own words, when they matter';

-- Favor.kt (M3 §6.2): a weighted mean of the valences of the interactions that count, with a neutral prior of three
-- interactions; weight 0.7 · 2^(-age / 10 h) + 0.3 · 2^(-age / 100 h), age in the player's own hours of play. The trend
-- is the favor now minus the favor 5 h of the player's play earlier, from the interactions known then. FavorTest and
-- SqlViewsTest check it against Favor.kt; respect and attention stay in Favor.kt (the player sheet shows them).
CREATE OR REPLACE VIEW mem_favor AS
WITH clock AS (
    SELECT pl.player_uuid, pl.name, pl.playtime_s + coalesce((SELECT sum(epoch(now() - s.joined_at)) FROM sessions s
        WHERE s.player_uuid = pl.player_uuid AND s.left_at IS NULL), 0) AS played_s
    FROM players pl),
w AS (
    SELECT i.player_uuid, i.valence,
           0.7 * pow(2, -(c.played_s - i.played_s) / 36000) + 0.3 * pow(2, -(c.played_s - i.played_s) / 360000) AS w,
           CASE WHEN i.played_s <= c.played_s - 18000
                THEN 0.7 * pow(2, -(c.played_s - 18000 - i.played_s) / 36000) + 0.3 * pow(2, -(c.played_s - 18000 - i.played_s) / 360000)
           END AS w_before
    FROM interactions i
    JOIN clock c USING (player_uuid)
    WHERE i.counts AND NOT i.archived AND i.played_s <= c.played_s),
f AS (
    SELECT player_uuid, 100 * sum(valence * w) / (3 * (sum(w) + 3)) AS favor,
           100 * coalesce(sum(valence * w_before), 0) / (3 * (coalesce(sum(w_before), 0) + 3)) AS favor_before
    FROM w
    GROUP BY player_uuid)
SELECT c.name AS player, f.favor, f.favor - f.favor_before AS trend,
       (SELECT count(*) FROM interactions i WHERE i.player_uuid = c.player_uuid AND i.disrespect AND NOT i.archived) AS disrespect
FROM clock c
LEFT JOIN f USING (player_uuid);
COMMENT ON VIEW mem_favor IS 'Favor with the Narrator, -100 to 100; trend: change over the player''s last 5 h of play';
COMMENT ON COLUMN mem_favor.favor IS 'NULL: no conduct noted yet';
COMMENT ON COLUMN mem_favor.trend IS 'favor now minus favor 5 h of the player''s play ago';
COMMENT ON COLUMN mem_favor.disrespect IS 'lacks of respect toward the Narrator noted';

CREATE OR REPLACE VIEW mem_stats AS
SELECT p.name AS player, s.stat, s.value, s.detail
FROM sheet_stats s
JOIN players p USING (player_uuid);
COMMENT ON VIEW mem_stats IS 'Stats the code computes: deaths, kills, mining, travel (m), gear (0-5), luck (0-100), style…';
COMMENT ON COLUMN mem_stats.stat IS 'playtime, deaths, kills, pvp, mining, building, travel, exploration, gear, advancements, economy, places, narrator, luck, style or signals';
COMMENT ON COLUMN mem_stats.value IS 'the main figure: seconds, count, metres, tier or index';
COMMENT ON COLUMN mem_stats.detail IS 'the breakdown, e.g. deaths: top causes, last death, absurd ones';

CREATE OR REPLACE VIEW mem_chronicle AS
SELECT 'H-' || id AS ref, level, period_from, period_to, title, summary
FROM chronicle
WHERE superseded_by IS NULL;
COMMENT ON VIEW mem_chronicle IS 'The chronicle (French) by chapter, session, season and saga';
COMMENT ON COLUMN mem_chronicle.ref IS 'its reference for the memory tools, e.g. H-88';
COMMENT ON COLUMN mem_chronicle.level IS 'chapter, session, season or saga';
