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
}
