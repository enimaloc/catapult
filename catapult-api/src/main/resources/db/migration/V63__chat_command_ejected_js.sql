-- Adds the "eject to JS" column for the block/DSL editor's Phase 2 escape hatch.
-- Reversible by design: `ast`/`template` are never overwritten by an eject, so reverting to
-- Blocks/Text just means clearing this column — the AST-driven editing state is untouched.
-- When populated, dispatch (DynamicChatCommand) runs this JS directly instead of compiling
-- `ast`, in the same sandboxed GraalJS engine (no elevated trust/whitelist — same ctx API,
-- same restrictions).
ALTER TABLE chat_command_definition
    ADD COLUMN ejected_js TEXT;
