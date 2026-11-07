package fr.enimaloc.catapult.chat.command.ast;

/**
 * Reads one of {@link fr.enimaloc.catapult.chat.DynamicChatCommand}'s known named lists
 * ({@code fallbacks}, {@code args}, {@code ownCommands}, {@code gameDlcs}, {@code similarGames},
 * {@code activeTws}, {@code allTws}) as a value, usable anywhere any other expression is (assigned to a variable,
 * passed as a service-call argument, compared in an {@code if}, ...) — {@link ForEachStatement}
 * already reads the same named lists but only for iteration; this is the same underlying {@code
 * ctx.list(name)} lookup exposed as its own expression instead of being wired into one statement
 * shape, e.g. {@code {var allArgs = list(args)}} then {@code {arr#join(allArgs, " ")}}.
 */
public record ListGetExpr(String name) implements Expression {
    @Override
    public String typeName() {
        return "list-get";
    }
}
