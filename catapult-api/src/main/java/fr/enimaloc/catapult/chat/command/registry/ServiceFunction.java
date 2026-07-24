package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.domain.UserAccount;

import java.util.List;

/**
 * A whitelisted function callable from sandboxed command JS, e.g. igdb#getGame.
 * Register new ones as Spring beans consumed by ServiceFunctionRegistry —
 * no change to the parser/generator/compiler is needed to add one.
 *
 * <p>{@code user} is the streamer whose command is currently executing — {@code null} for
 * callers that don't have one (e.g. tests exercising a function that doesn't need it). Functions
 * operating on public, non-user-scoped data (igdb#getGame, steam#getPrice) simply ignore it;
 * functions scoped to "the current streamer's own X" (any Twitch or Catapult-account function)
 * require it.
 */
public interface ServiceFunction {
    String namespace();
    String name();
    List<String> parameterNames();
    Object invoke(UserAccount user, Object[] args) throws Exception;

    /** OAuth scopes (beyond what's already granted) this function needs to work. */
    default List<String> requiredScopes() {
        return List.of();
    }

    /**
     * Field names of the object this function returns, empty for scalar-returning functions.
     * Purely descriptive (the sandbox doesn't enforce it) — lets the Blocks editor pre-fill a
     * property-access dropdown instead of the streamer having to type a field name from memory
     * and risk a silent {@code undefined} on a typo.
     */
    default List<String> returnKeys() {
        return List.of();
    }

    /**
     * Subset of {@link #parameterNames()} that may be omitted by the caller — a trailing
     * argument the streamer left unconnected in the Blocks editor already compiles to a
     * {@code ""} literal, but the text DSL and any hand-edited "ejected" JS can call {@code
     * ctx.call(...)} with fewer arguments than {@link #parameterNames()} declares, so {@code
     * invoke(...)} must read optional trailing arguments via {@link #optionalArg(Object[], int)}
     * rather than indexing {@code args} directly. Purely descriptive otherwise (not enforced by
     * the sandbox) — lets the Blocks editor mark the corresponding slot's label as optional.
     */
    default List<String> optionalParameterNames() {
        return List.of();
    }

    /** {@code ""} (the DSL's universal "missing" value) when {@code index} wasn't supplied. */
    static String optionalArg(Object[] args, int index) {
        return index < args.length ? String.valueOf(args[index]) : "";
    }
}
