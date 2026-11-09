-- Adds the AST representation used by the block/DSL editor.
-- `template` is kept read-only for rollback/audit; execution reads `ast` once populated.
ALTER TABLE chat_command_definition
    ADD COLUMN ast TEXT;
