-- Style and ideas (design M6 §12.3): the storyteller's ideas, and the line review of each line of the Director.
-- No older table changes: a released migration is never edited.

-- Ideas of the storyteller (M6 §6.2): rows of the memory, cited as P-<id>, on the memory's one id sequence
-- (IdKind.MEMORY). Never canon: an idea is no promise. Nothing is deleted: an idea closes (dropped, expired).
CREATE TABLE ideas (
    id             BIGINT PRIMARY KEY,         -- shown as P-<id>
    created_at     TIMESTAMPTZ NOT NULL,
    kind           VARCHAR NOT NULL,           -- season | arc_step | challenge | duel | punishment | favor | law | plan
                                               -- | module | forge | speech | twist
    title          VARCHAR NOT NULL,           -- ≤ 60, French
    pitch          VARCHAR NOT NULL,           -- ≤ 500, French, impersonal or third person
    fit            VARCHAR NOT NULL,           -- ≤ 200: when it fits
    players        UUID[] NOT NULL,            -- empty = whoever is online
    register       VARCHAR,                    -- a comic register id (ComicRegister)
    novelty        TINYINT NOT NULL, fun TINYINT NOT NULL, fairness TINYINT NOT NULL,   -- 1..5, the storyteller's own
    refs           BIGINT[] NOT NULL,          -- the C-, M-, H- rows it builds on
    module         VARCHAR,                    -- kind module: a ready module of the arsenal
    brief          VARCHAR,                    -- kind forge: the forge_module brief, ≤ 1 500
    status         VARCHAR NOT NULL,           -- open | dropped | expired
    dealt          INTEGER NOT NULL DEFAULT 0, -- times put before the Director (inspire, a heartbeat)
    last_dealt_at  TIMESTAMPTZ,
    expires_play_s BIGINT NOT NULL,            -- world play seconds at which it expires
    closed_at      TIMESTAMPTZ, close_reason VARCHAR,
    source         VARCHAR NOT NULL, run_id BIGINT, conversation_id VARCHAR
);

-- The line review (M6 §3.3): the small model's labels on each line of the Director, append only like chat_signals.
-- A line is labeled whole or not at all: the labels are never NULL.
CREATE TABLE line_labels (
    event_id         BIGINT NOT NULL,          -- the chat row of the line (channel narrator)
    ts               TIMESTAMPTZ NOT NULL,
    source           VARCHAR NOT NULL,         -- reflex:<model>
    register         VARCHAR NOT NULL,         -- a comic register id (ComicRegister), plain or other
    dark DOUBLE NOT NULL, cruel DOUBLE NOT NULL, flat DOUBLE NOT NULL, out_of_character DOUBLE NOT NULL,
    repeats DOUBLE NOT NULL,                   -- 0..1
    crutches         VARCHAR[] NOT NULL,       -- the crutch words of the line, 3 at most
    reason           VARCHAR,                  -- the reviewer's one sentence: it quotes the Narrator, never a player
    latency_ms       INTEGER
);

COMMENT ON TABLE ideas IS 'Ideas the storyteller prepared for the Narrator (refs P-…): open, dropped or expired';
COMMENT ON TABLE line_labels IS 'The line review: comic register and flaws (0 to 1) of each line of the Narrator';
COMMENT ON COLUMN line_labels.event_id IS 'id of the chat line (channel narrator)';
COMMENT ON COLUMN line_labels.source IS 'reflex: followed by the model id (reflex:claude-haiku-5-5)';
COMMENT ON COLUMN line_labels.crutches IS 'words the line uses as a tic of style, not as content';
