-- Rewrite legacy {dtdd.yes|no|mostly} placeholders to {tw.active}, preserving inline fallbacks.
UPDATE chat_command_definition
SET template = regexp_replace(
  template,
  '\{dtdd\.(yes|no|mostly)(\|[^}]*)?\}',
  '{tw.active\2}',
  'g'
)
WHERE template ~ '\{dtdd\.(yes|no|mostly)';
