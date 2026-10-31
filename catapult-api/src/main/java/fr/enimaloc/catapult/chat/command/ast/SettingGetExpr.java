package fr.enimaloc.catapult.chat.command.ast;

/** Reads a streamer-defined {@code key=value} setting (see {@code ChatCommandSetting}), parsed
 *  from the {@code ctx.settings.<key>} dot-chain — a flat key, never nested further. Distinct
 *  from a chat command's own arguments (the words typed after the command name). */
public record SettingGetExpr(String key) implements Expression {
    @Override
    public String typeName() {
        return "setting-get";
    }
}
