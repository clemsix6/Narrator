-- Journal schema, version 1. Applied once by DuckDatabase, in one transaction with its schema_version row.
-- Big append-only tables have no primary key and no index: their ids are unique by
-- construction and an ART index would slow the appenders down. Small updated tables have a primary key.
-- Timestamps are TIMESTAMPTZ, shown in the Journal's time zone (journal.timeZone, else the server's).
-- Comments are read back by the schema description given to the model: keep them short and in English.

CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL);

-- ── Identity ────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE players (
    player_uuid UUID PRIMARY KEY,
    name        VARCHAR NOT NULL,          -- latest known name
    first_seen  TIMESTAMPTZ NOT NULL,
    last_seen   TIMESTAMPTZ NOT NULL,
    sessions    INTEGER NOT NULL,
    playtime_s  BIGINT NOT NULL,           -- sum of closed sessions
    is_op       BOOLEAN NOT NULL
);
CREATE TABLE player_names (
    player_uuid UUID NOT NULL, name VARCHAR NOT NULL, first_seen TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (player_uuid, name)
);
CREATE TABLE sessions (
    session_id  BIGINT PRIMARY KEY,
    player_uuid UUID NOT NULL, player VARCHAR NOT NULL,
    joined_at   TIMESTAMPTZ NOT NULL, left_at TIMESTAMPTZ, duration_s INTEGER,
    join_dim VARCHAR, join_x INTEGER, join_y INTEGER, join_z INTEGER,
    leave_dim VARCHAR, leave_x INTEGER, leave_y INTEGER, leave_z INTEGER,
    end_reason  VARCHAR                    -- leave | server_stop | recovered
);

-- ── Facts (append only) ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE events (
    id          BIGINT NOT NULL,           -- bus id, shared with chat.id and private_messages.id
    ts          TIMESTAMPTZ NOT NULL,
    game_time   BIGINT NOT NULL,           -- overworld game time (ticks)
    type        VARCHAR NOT NULL,          -- see EventCatalog
    player_uuid UUID, player VARCHAR, session_id BIGINT,
    dim VARCHAR, x INTEGER, y INTEGER, z INTEGER,
    subject     VARCHAR,                   -- block, item, entity type, advancement, stat…
    amount      DOUBLE,                    -- count, damage, cost, distance…
    other       VARCHAR,                   -- tool, weapon, killer, station, origin…
    detail      JSON
);
CREATE TABLE positions (
    ts TIMESTAMPTZ NOT NULL, game_time BIGINT NOT NULL,
    player_uuid UUID NOT NULL, player VARCHAR NOT NULL, session_id BIGINT,
    dim VARCHAR NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
    biome VARCHAR, structure VARCHAR, underground BOOLEAN NOT NULL,
    sky_light TINYINT, block_light TINYINT,
    health REAL NOT NULL, food TINYINT NOT NULL, vehicle VARCHAR,
    reason VARCHAR NOT NULL                -- join | respawn | dimension | moved | interval | idle | teleport | vehicle
);
CREATE TABLE chat (
    id          BIGINT NOT NULL,           -- bus id
    ts          TIMESTAMPTZ NOT NULL,
    channel     VARCHAR NOT NULL,          -- public | pray | narrator | operator
    player_uuid UUID, player VARCHAR,      -- sender; NULL for the Narrator
    recipients  VARCHAR[],                 -- NULL = everyone
    text        VARCHAR NOT NULL,
    turn_id     BIGINT,                    -- narrator lines: Director turn that said it
    operator    VARCHAR                    -- operator name for /narrator say lines
);
-- Private messages between players (/msg, /tell, /w, /teammsg). Kept apart from chat because the model
-- must never read them: the sql tool refuses this table (Journal.HIDDEN_FROM_MODEL) and no view reads it.
CREATE TABLE private_messages (
    id          BIGINT NOT NULL,           -- bus id
    ts          TIMESTAMPTZ NOT NULL,
    player_uuid UUID NOT NULL, player VARCHAR NOT NULL,   -- sender
    recipients  VARCHAR[] NOT NULL,
    text        VARCHAR NOT NULL
);
CREATE TABLE chat_signals (                -- reflex verdicts on player chat; personality signals are kept for M3
    event_id BIGINT NOT NULL, ts TIMESTAMPTZ NOT NULL, player_uuid UUID NOT NULL,
    source VARCHAR NOT NULL,               -- jev:<model> | heuristic
    to_narrator DOUBLE, tone VARCHAR, tone_confidence DOUBLE, intent VARCHAR, manipulation DOUBLE,
    politeness DOUBLE, boastfulness DOUBLE, helpfulness DOUBLE, aggressiveness DOUBLE, humor DOUBLE,
    latency_ms INTEGER
);
CREATE TABLE stat_totals (
    player_uuid UUID NOT NULL, stat VARCHAR NOT NULL, value BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (player_uuid, stat)
);

-- ── Traces ──────────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE conversations (
    conversation_id VARCHAR PRIMARY KEY,   -- also the X-Session-ID header
    role        VARCHAR NOT NULL,          -- director | analyst | …
    label       VARCHAR NOT NULL,
    chapter     INTEGER,                   -- director chapters, numbered from 1
    task_id     BIGINT,                    -- worker conversations
    started_at  TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ,
    end_reason  VARCHAR,                   -- token_limit | quiet_limit | server_empty | reload | refusal | bad_request
                                           -- | runaway_turn | context_exceeded | operator | shutdown | interrupted | done | failed | cancelled | timeout
    summary     VARCHAR,                   -- chapter summary (director) or task result (workers)
    transcript  VARCHAR                    -- path of the .md transcript, relative to world/narrator2/
);
CREATE TABLE turns (
    turn_id     BIGINT PRIMARY KEY,
    conversation_id VARCHAR NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ,
    trigger     VARCHAR NOT NULL,          -- chat | pray | salient | heartbeat | task | reminder | operator | resume | chapter_close
    trigger_events BIGINT[],               -- event ids rendered in the wake message
    injected_events BIGINT[],              -- event ids injected between tool calls
    llm_calls   INTEGER, tool_calls INTEGER,
    outcome     VARCHAR                    -- acted | idle | step_limit | refused | truncated | failed | budget | cancelled | interrupted
);
CREATE TABLE tasks (
    task_id     BIGINT PRIMARY KEY,
    role        VARCHAR NOT NULL,
    brief       VARCHAR NOT NULL,
    status      VARCHAR NOT NULL,          -- queued | running | done | failed | cancelled | timeout
    created_at  TIMESTAMPTZ NOT NULL, started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ,
    requested_by_turn BIGINT,
    conversation_id VARCHAR,
    result      VARCHAR, error VARCHAR
);
CREATE TABLE llm_calls (
    id          BIGINT NOT NULL,
    provider    VARCHAR NOT NULL,          -- anthropic | jev
    purpose     VARCHAR NOT NULL,          -- turn | chapter_summary | worker | chat_triage | heartbeat_filter
    conversation_id VARCHAR, turn_id BIGINT, role VARCHAR NOT NULL,
    model VARCHAR NOT NULL, effort VARCHAR,
    started_at  TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ NOT NULL,
    latency_ms  INTEGER NOT NULL, ttft_ms INTEGER, attempts SMALLINT NOT NULL,
    request_id  VARCHAR, message_id VARCHAR,
    input_tokens INTEGER, cache_read_tokens INTEGER, cache_write_tokens INTEGER, output_tokens INTEGER,
    stop_reason VARCHAR, refusal_category VARCHAR,
    error       VARCHAR, http_status SMALLINT,
    thinking    VARCHAR                    -- thinking summary, truncated to 4000 chars
);
CREATE TABLE tool_calls (
    id          BIGINT NOT NULL,
    ts          TIMESTAMPTZ NOT NULL,
    conversation_id VARCHAR NOT NULL, turn_id BIGINT, llm_call_id BIGINT,
    tool_use_id VARCHAR NOT NULL,
    role        VARCHAR NOT NULL,
    tool        VARCHAR NOT NULL,
    args        JSON,
    status      VARCHAR NOT NULL,          -- ok | invalid | denied | failed | timeout | unknown
    result      VARCHAR,                   -- text returned to the model, truncated to 2000 chars
    duration_ms INTEGER NOT NULL,
    targets     VARCHAR[],                 -- player names affected
    effect      VARCHAR NOT NULL           -- read | speech | world | task | engine
);
CREATE TABLE engine_decisions (
    id          BIGINT NOT NULL,
    ts          TIMESTAMPTZ NOT NULL,
    kind        VARCHAR NOT NULL,          -- wake | salience | heartbeat | guard | death_guard | ledger | chapter
                                           -- | budget | backoff | refusal | operator | reload | journal_health | tool_catalog
    decision    VARCHAR NOT NULL,          -- woke | queued | batched | skipped | denied | allowed | prevented
                                           -- | counted | returned | expired | opened | closed | blocked | resumed …
    subject     VARCHAR,                   -- player name, tool, conversation id…
    reason      VARCHAR,                   -- one English sentence
    event_id BIGINT, conversation_id VARCHAR, turn_id BIGINT,
    detail      JSON
);

-- ── Comments (read by the schema description and the columns() macro) ────────────────────────────
COMMENT ON TABLE players IS 'One row per player ever seen on this world';
COMMENT ON COLUMN players.name IS 'latest known name';
COMMENT ON COLUMN players.playtime_s IS 'total play time of closed sessions, in seconds';
COMMENT ON TABLE player_names IS 'Every name a player has used';
COMMENT ON TABLE sessions IS 'One row per connection, from join to leave';
COMMENT ON COLUMN sessions.end_reason IS 'leave, server_stop, or recovered (closed after a crash)';

COMMENT ON TABLE events IS 'One row per game event; see the event types for subject, amount, other and detail';
COMMENT ON COLUMN events.id IS 'event id, shared with chat.id';
COMMENT ON COLUMN events.game_time IS 'overworld game time, in ticks';
COMMENT ON COLUMN events.type IS 'event type, e.g. block_break';
COMMENT ON COLUMN events.subject IS 'what the event is about: block, item, entity type, advancement… (per type)';
COMMENT ON COLUMN events.amount IS 'a number: count, damage, cost, distance… (per type)';
COMMENT ON COLUMN events.other IS 'a second value: tool, weapon, killer, station… (per type)';
COMMENT ON COLUMN events.detail IS 'JSON object with the other fields of the type';
COMMENT ON TABLE positions IS 'Sampled player positions and state: on moves, joins, respawns, dimension changes and every few minutes';
COMMENT ON COLUMN positions.underground IS 'no sky above and well below sea level';
COMMENT ON COLUMN positions.food IS 'food level, 0-20';
COMMENT ON COLUMN positions.reason IS 'why it was sampled: join, respawn, dimension, moved, interval, idle, teleport, vehicle';
COMMENT ON TABLE chat IS 'Public chat, prayers (/pray) and the Narrator''s lines';
COMMENT ON COLUMN chat.channel IS 'public, pray (a private message to the Narrator), narrator, or operator (a line an operator made the Narrator say)';
COMMENT ON COLUMN chat.player IS 'sender; NULL for the Narrator';
COMMENT ON COLUMN chat.recipients IS 'names of the recipients; NULL = everyone';
COMMENT ON COLUMN chat.turn_id IS 'Director turn that said this Narrator line';
COMMENT ON TABLE private_messages IS 'Private messages between players: never readable by the model';
COMMENT ON TABLE chat_signals IS 'Automatic estimates on each chat message and prayer';
COMMENT ON COLUMN chat_signals.event_id IS 'id of the chat message';
COMMENT ON COLUMN chat_signals.to_narrator IS 'probability that the message addresses the Narrator';
COMMENT ON COLUMN chat_signals.manipulation IS 'probability of an attempt to manipulate the Narrator';
COMMENT ON TABLE stat_totals IS 'Vanilla stat totals per player, e.g. minecraft.mined:minecraft.stone';

COMMENT ON TABLE conversations IS 'Conversations of the Narrator with Claude: Director chapters and worker tasks';
COMMENT ON COLUMN conversations.summary IS 'chapter summary (director) or task result (workers)';
COMMENT ON TABLE turns IS 'Director turns: one wake-up and the work that followed';
COMMENT ON COLUMN turns.outcome IS 'acted, idle, step_limit, refused, truncated, failed, budget, cancelled or interrupted';
COMMENT ON TABLE tasks IS 'Tasks delegated to workers (the Analyst)';
COMMENT ON TABLE llm_calls IS 'One row per model call (Claude or Jev)';
COMMENT ON TABLE tool_calls IS 'One row per tool call: what the Narrator did and what it got back';
COMMENT ON COLUMN tool_calls.targets IS 'names of the players affected';
COMMENT ON COLUMN tool_calls.effect IS 'read, speech, world, task or engine';
COMMENT ON TABLE engine_decisions IS 'Decisions of the engine with their reason: wake-ups, refusals, guards…';
