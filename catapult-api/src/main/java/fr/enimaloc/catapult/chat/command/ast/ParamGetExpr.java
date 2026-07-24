package fr.enimaloc.catapult.chat.command.ast;

/** Reads a streamer-defined {@code key=value} param (see {@code ChatCommandParam}), parsed from
 *  the {@code ctx.params.<key>} dot-chain — a flat key, never nested further. */
public record ParamGetExpr(String key) implements Expression {
    @Override
    public String typeName() {
        return "param-get";
    }
}
