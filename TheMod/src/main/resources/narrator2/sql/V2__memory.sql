-- Memory schema, version 2 (design M3 §3.3). Applied once by DuckDatabase, in one transaction with its schema_version row.
-- Small updated tables have a primary key and only update their non-key columns; append-only tables
-- (memory_log, recalls, search_postings) have none. Nothing is ever deleted: rows are archived, merged
-- or superseded, and every change is kept in memory_log.
-- Texts written by a model are French, third person; codes (kind, status, tags) are lowercase English ids.
-- Ids: every citable row (memories, canon, interactions, chronicle, notes) takes its id from one sequence
-- (IdKind.MEMORY), so an id names one row whatever its table; memory_runs.run_id is IdKind.MEMORY_RUN.
-- Table comments are read back by columns_of(); columns are only described in the mem_* views.

-- ── Memory state ────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE memory_state (          -- small key/value state of the memory jobs: world_play_s, last windows, …
    key VARCHAR PRIMARY KEY, value VARCHAR NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);

-- ── Episodic memories ───────────────────────────────────────────────────────────────────────────────
CREATE TABLE memories (
    id               BIGINT PRIMARY KEY,         -- IdKind.MEMORY; shown as M-<id>
    kind             VARCHAR NOT NULL,           -- episode | observation | reflection
    created_at       TIMESTAMPTZ NOT NULL,
    occurred_from    TIMESTAMPTZ NOT NULL,
    occurred_to      TIMESTAMPTZ NOT NULL,
    title            VARCHAR NOT NULL,           -- ≤ 80 chars
    content          VARCHAR NOT NULL,           -- ≤ 400 chars, French, third person
    players          UUID[] NOT NULL,            -- subjects, main one first; empty = the whole server
    entities         VARCHAR[] NOT NULL,         -- game ids (minecraft:trial_chambers) and refs (C-45)
    tags             VARCHAR[] NOT NULL,         -- controlled vocabulary (M3 §3.6)
    importance       TINYINT NOT NULL,           -- 1..10
    valence          TINYINT NOT NULL,           -- -3..+3, for the main player
    dim VARCHAR, x INTEGER, y INTEGER, z INTEGER,   -- where it happened (from the evidence), if anywhere
    stability_h      DOUBLE NOT NULL,            -- half-life in hours of world play (M3 §9.1)
    reinforced_play_s BIGINT NOT NULL,           -- world play clock at creation or last reinforcement
    recalls          INTEGER NOT NULL DEFAULT 0,
    last_recalled_at TIMESTAMPTZ,
    source           VARCHAR NOT NULL,           -- director | chronicler | dreamer | engine | operator
    run_id BIGINT, turn_id BIGINT, conversation_id VARCHAR,
    evidence_events  BIGINT[] NOT NULL,          -- events.id / chat.id
    evidence_tools   BIGINT[] NOT NULL,          -- tool_calls.id
    archived         BOOLEAN NOT NULL DEFAULT false,
    merged_into      BIGINT                      -- M-id that absorbed this one
);

-- ── Canon: what the Narrator stated, promised, decided ──────────────────────────────────────────────
CREATE TABLE canon (
    id              BIGINT PRIMARY KEY,          -- shown as C-<id>
    kind            VARCHAR NOT NULL,            -- promise | threat | challenge | law | prophecy | nickname | title
                                                 -- | place | lore | opinion | running_gag | arc | claim
    created_at      TIMESTAMPTZ NOT NULL,
    statement       VARCHAR NOT NULL,            -- ≤ 300 chars, French, third person: what it says
    quote           VARCHAR,                     -- ≤ 200 chars: the Narrator's exact words, when said to players
    players         UUID[] NOT NULL,             -- to whom / about whom; empty = everyone
    stakes          VARCHAR,                     -- challenge, bet: reward and penalty (≤ 160)
    importance      TINYINT NOT NULL,            -- 1..10
    status          VARCHAR NOT NULL,            -- planned | active | fulfilled | broken | expired | won | lost
                                                 -- | abandoned | cancelled | resolved | retired (M3 §3.6)
    due_at          TIMESTAMPTZ, due_signaled_at TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ, resolution VARCHAR, winners UUID[],
    replaces        BIGINT,                      -- the entry this one supersedes (new nickname, title)
    dim VARCHAR, x INTEGER, y INTEGER, z INTEGER,   -- places; else where it was said
    uses            INTEGER NOT NULL DEFAULT 0,  -- running gags, nicknames: times served
    last_used_at    TIMESTAMPTZ,
    freshness       VARCHAR,                     -- running gags: fresh | worn | retired
    source          VARCHAR NOT NULL, run_id BIGINT, turn_id BIGINT, conversation_id VARCHAR,
    evidence_events BIGINT[] NOT NULL, evidence_tools BIGINT[] NOT NULL,
    archived        BOOLEAN NOT NULL DEFAULT false
);

-- ── Rated interactions ──────────────────────────────────────────────────────────────────────────────
CREATE TABLE interactions (
    id              BIGINT PRIMARY KEY,          -- shown as I-<id>
    ts              TIMESTAMPTZ NOT NULL,
    player_uuid     UUID NOT NULL, player VARCHAR NOT NULL,   -- name at the time
    kind            VARCHAR NOT NULL,            -- M3 §3.6
    valence         TINYINT NOT NULL,            -- -3..+3: how it reflects on the player, in the Narrator's eyes
    counts          BOOLEAN NOT NULL,            -- counts toward favor: the player's behavior, not the Narrator's answer
    disrespect      BOOLEAN NOT NULL,            -- a lack of respect toward the Narrator, kept on the sheet
    summary         VARCHAR NOT NULL,            -- ≤ 160 chars, French, third person
    quote           VARCHAR,                     -- ≤ 120 chars: the player's own words, when they matter
    played_s        BIGINT NOT NULL,             -- the player's play clock at ts
    canon_id BIGINT, memory_id BIGINT,
    tool_calls      BIGINT[] NOT NULL,           -- punishments, blessings, gifts: the tool calls that did it
    source          VARCHAR NOT NULL, run_id BIGINT, turn_id BIGINT, conversation_id VARCHAR,
    evidence_events BIGINT[] NOT NULL,
    archived        BOOLEAN NOT NULL DEFAULT false
);

-- ── Sheet: personality (the Dreamer), one current row per key; the history is in memory_log ────────
CREATE TABLE player_traits (
    player_uuid UUID NOT NULL, trait VARCHAR NOT NULL,   -- axis id (boldness…) or free:<slug>
    side        TINYINT NOT NULL,            -- -1 low pole, +1 high pole; 0 for a free trait
    label       VARCHAR,                     -- free traits: their French label (≤ 40)
    intensity   TINYINT NOT NULL,            -- 1..5
    confidence  DOUBLE NOT NULL,             -- 0..1 when set; decays at read time (M3 §9.3)
    evidence    BIGINT[] NOT NULL,           -- M- and I- ids (at least one)
    since       TIMESTAMPTZ NOT NULL,        -- first set on this side
    trend       TINYINT NOT NULL,            -- -1 weaker, 0 same, +1 stronger than the previous value
    note        VARCHAR,                     -- ≤ 160
    set_at      TIMESTAMPTZ NOT NULL, set_played_s BIGINT NOT NULL, run_id BIGINT,
    retired     BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (player_uuid, trait)
);
CREATE TABLE player_skills (
    player_uuid UUID NOT NULL, skill VARCHAR NOT NULL,   -- pve | pvp | building | mining | exploration | survival | luck
    score TINYINT NOT NULL,                  -- 0..10
    rationale VARCHAR NOT NULL,              -- ≤ 200, cites the stats
    evidence BIGINT[] NOT NULL, set_at TIMESTAMPTZ NOT NULL, run_id BIGINT,
    PRIMARY KEY (player_uuid, skill)
);
CREATE TABLE player_relations (
    player_uuid UUID NOT NULL, other_uuid UUID NOT NULL,   -- directed: how player_uuid stands toward other_uuid
    kind VARCHAR NOT NULL,                   -- duo | ally | rival | enemy | protector | protege | mentor | student
                                             -- | scapegoat | tormentor | strangers
    note VARCHAR NOT NULL, evidence BIGINT[] NOT NULL, set_at TIMESTAMPTZ NOT NULL, run_id BIGINT,
    PRIMARY KEY (player_uuid, other_uuid)
);

-- ── Sheet: what the code computes (never the model) ─────────────────────────────────────────────────
CREATE TABLE sheet_stats (
    player_uuid UUID NOT NULL, stat VARCHAR NOT NULL,   -- M3 §6.1
    value DOUBLE, detail JSON, computed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (player_uuid, stat)
);
CREATE TABLE pair_stats (                    -- a_uuid < b_uuid
    a_uuid UUID NOT NULL, b_uuid UUID NOT NULL,
    minutes_together DOUBLE NOT NULL, minutes_together_7d DOUBLE NOT NULL,
    pvp_a_on_b INTEGER NOT NULL, pvp_b_on_a INTEGER NOT NULL,
    griefs_a_on_b INTEGER NOT NULL, griefs_b_on_a INTEGER NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (a_uuid, b_uuid)
);

-- ── Written documents, versioned (append only) ──────────────────────────────────────────────────────
CREATE TABLE memory_docs (
    key         VARCHAR NOT NULL,            -- grimoire | review | sheet:<uuid>:<section> (M3 §3.6)
    version     INTEGER NOT NULL,            -- 1, 2, 3…; the current one is the highest
    content     VARCHAR NOT NULL,
    evidence    BIGINT[] NOT NULL,
    written_at  TIMESTAMPTZ NOT NULL,
    source      VARCHAR NOT NULL, run_id BIGINT,
    restored_from INTEGER,                   -- a rollback copies an older version into a new one
    PRIMARY KEY (key, version)
);

-- ── Hierarchical chronicle ──────────────────────────────────────────────────────────────────────────
CREATE TABLE chronicle (
    id            BIGINT PRIMARY KEY,        -- shown as H-<id>
    level         VARCHAR NOT NULL,          -- chapter | session | season | saga
    period_from   TIMESTAMPTZ NOT NULL, period_to TIMESTAMPTZ NOT NULL,
    title         VARCHAR NOT NULL,          -- ≤ 80
    summary       VARCHAR NOT NULL,          -- budget by level (M3 §8.5)
    covers        BIGINT[] NOT NULL,         -- H- ids one level down (empty for chapters)
    conversation_id VARCHAR,                 -- chapter: the Director's conversation
    open          BOOLEAN NOT NULL,          -- the season in progress and the saga are rewritten
    superseded_by BIGINT,                    -- rewritten entries point to their successor
    written_at    TIMESTAMPTZ NOT NULL, source VARCHAR NOT NULL, run_id BIGINT
);

-- ── Notes for the Director ──────────────────────────────────────────────────────────────────────────
CREATE TABLE notes (
    id BIGINT PRIMARY KEY,                   -- shown as N-<id>
    created_at TIMESTAMPTZ NOT NULL,
    kind VARCHAR NOT NULL,                   -- coherence | dream_hint | expired | operator
    text VARCHAR NOT NULL,                   -- ≤ 300, French, third person
    players UUID[] NOT NULL, refs BIGINT[] NOT NULL,
    status VARCHAR NOT NULL,                 -- open | shown | done
    shown_count INTEGER NOT NULL DEFAULT 0, last_shown_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    source VARCHAR NOT NULL, run_id BIGINT
);

-- ── Recalls: each time a row was put before a model (append only) ───────────────────────────────────
CREATE TABLE recalls (
    ts TIMESTAMPTZ NOT NULL, ref_id BIGINT NOT NULL,
    channel VARCHAR NOT NULL,                -- briefing | join | reminiscence | recall_tool | profile | dream | used
    conversation_id VARCHAR, turn_id BIGINT, event_id BIGINT
);

-- ── Memory runs ─────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE memory_runs (
    run_id       BIGINT PRIMARY KEY,         -- IdKind.MEMORY_RUN
    kind         VARCHAR NOT NULL,           -- batch | nap | dream | stats | hygiene
    trigger      VARCHAR NOT NULL,           -- timer | chapter_closed | server_empty | daily | operator | startup
    status       VARCHAR NOT NULL,           -- running | done | failed | cancelled | interrupted | skipped
    started_at   TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ,
    window_from  TIMESTAMPTZ, window_to TIMESTAMPTZ,
    chapter      VARCHAR,                    -- the Director's conversation (batch, nap)
    conversations VARCHAR[] NOT NULL,        -- the model conversations of the run
    writes       INTEGER NOT NULL DEFAULT 0, rejected INTEGER NOT NULL DEFAULT 0,
    input_tokens BIGINT, output_tokens BIGINT,
    report       VARCHAR, error VARCHAR
);

-- ── Audit of every change (append only) ─────────────────────────────────────────────────────────────
CREATE TABLE memory_log (
    ts TIMESTAMPTZ NOT NULL,
    actor VARCHAR NOT NULL,                  -- director | chronicler | dreamer | engine | operator:<name>
    run_id BIGINT, turn_id BIGINT,
    table_name VARCHAR NOT NULL, row_key VARCHAR NOT NULL,   -- "812", "b1f…:boldness", "grimoire:12"
    op VARCHAR NOT NULL,                     -- insert | update | archive | restore | merge | rollback | revert
    before JSON, after JSON,
    reason VARCHAR
);

-- ── Full-text index (M3 §10.3) ──────────────────────────────────────────────────────────────────────
CREATE TABLE search_docs (
    ref_id BIGINT PRIMARY KEY, kind VARCHAR NOT NULL,   -- memory | canon | interaction | chronicle
    len INTEGER NOT NULL                                -- number of terms
);
CREATE TABLE search_postings (ref_id BIGINT NOT NULL, term VARCHAR NOT NULL, tf SMALLINT NOT NULL);

COMMENT ON TABLE memory_state IS 'Key/value state of the memory jobs, such as the world play clock';
COMMENT ON TABLE memories IS 'The Narrator''s episodic memories (refs M-…), French, third person';
COMMENT ON TABLE canon IS 'What the Narrator stated, promised or decided (refs C-…), with its status and due time';
COMMENT ON TABLE interactions IS 'Rated interactions between a player and the Narrator (refs I-…)';
COMMENT ON TABLE player_traits IS 'Personality traits of each player, one row per axis, with their evidence';
COMMENT ON TABLE player_skills IS 'Skill scores of each player, 0 to 10, with their rationale';
COMMENT ON TABLE player_relations IS 'How a player stands toward another player';
COMMENT ON TABLE sheet_stats IS 'Player sheet statistics computed by the engine from the Journal';
COMMENT ON TABLE pair_stats IS 'Statistics computed for each pair of players seen together';
COMMENT ON TABLE memory_docs IS 'Versioned documents: the Grimoire, the review and the sections of player sheets';
COMMENT ON TABLE chronicle IS 'The chronicle (refs H-…): chapters, sessions, seasons and the saga';
COMMENT ON TABLE notes IS 'Notes of the memory for the Director (refs N-…): incoherences, expiries, dream hints';
COMMENT ON TABLE recalls IS 'Each time a memory row was shown to a model, and through which channel';
COMMENT ON TABLE memory_runs IS 'Memory jobs: chronicler batches and naps, dreams, stats, hygiene';
COMMENT ON TABLE memory_log IS 'Audit of every change to the memory: before, after, author and job';
COMMENT ON TABLE search_docs IS 'Full-text index of the memory: one row per indexed row';
COMMENT ON TABLE search_postings IS 'Full-text index of the memory: term frequencies per indexed row';
