-- Clarity (M7-CLARITY): the line review scores a sixth flaw, obscure: a line a player who knows nothing of the mod
-- would not understand at the first read (invented jargon, a metaphor carried over from earlier lines, the engine's
-- words). The column comes last, after latency_ms; the rows written before it keep NULL (never scored).
ALTER TABLE line_labels ADD COLUMN obscure DOUBLE;  -- 0..1

COMMENT ON COLUMN line_labels.obscure IS 'how hard the line is to follow at the first read, 0 to 1; NULL in rows older than this flaw';
