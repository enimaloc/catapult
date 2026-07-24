package fr.enimaloc.catapult.chat.command.ast;

/** Reads one word of the chat command's own arguments — the text typed after the command name
 *  (e.g. {@code !test arg1 arg2} -> {@code args = ["arg1", "arg2"]}), by a literal non-negative
 *  integer index. Resolves to {@code ""} if that index wasn't supplied (see
 *  {@code DynamicChatCommand#resolveList}). Distinct from {@code ctx.settings.<key>}
 *  ({@link SettingGetExpr}), which reads a streamer-defined setting, not a per-invocation
 *  argument. */
public record ArgGetExpr(int index) implements Expression {
    @Override
    public String typeName() {
        return "arg-get";
    }
}
