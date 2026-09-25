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
