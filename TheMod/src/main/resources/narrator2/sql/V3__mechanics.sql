-- Mechanics (design M4 §3.3): what the engine tracks among the canon, and the themes of arcs.
-- No new table: the history of the mechanics is the canon, the interactions and the engine's decisions.
ALTER TABLE canon ADD COLUMN mechanic VARCHAR;  -- challenge | duel | rule: a row the engine tracks and resolves; NULL otherwise
ALTER TABLE canon ADD COLUMN theme VARCHAR;     -- arcs: their theme's slug, which rests once the arc is closed (M4 §10)

COMMENT ON COLUMN canon.mechanic IS 'challenge, duel or rule: the mechanics engine tracks and resolves this row; NULL otherwise';
COMMENT ON COLUMN canon.theme IS 'arcs: the slug of their theme, which rests for a while once the arc is closed';

-- Reflexes (REFLEX-HAIKU): Jev gave way to a small Claude model, the role reflex. A released migration is never
-- edited: the comments of V1 that named Jev are replaced here (rows written before keep provider jev).
-- This one is in the model's schema description (SchemaDoc, section TABLES): kept within its budget.
COMMENT ON TABLE llm_calls IS 'One row per Claude call, role reflex included';
COMMENT ON COLUMN llm_calls.provider IS 'anthropic (jev in rows older than the reflexes on Claude)';
COMMENT ON COLUMN llm_calls.role IS 'director, analyst, chronicler, dreamer, or reflex: the small model that reads the chat';
COMMENT ON COLUMN chat_signals.source IS 'reflex: followed by the model id (reflex:claude-haiku-5-5), or heuristic';
