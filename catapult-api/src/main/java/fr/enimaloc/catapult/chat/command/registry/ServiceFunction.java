package fr.enimaloc.catapult.chat.command.registry;

import java.util.List;

/**
 * A whitelisted function callable from sandboxed command JS, e.g. igdb#getGame.
 * Register new ones as Spring beans consumed by ServiceFunctionRegistry —
 * no change to the parser/generator/compiler is needed to add one.
 */
public interface ServiceFunction {
    String namespace();
    String name();
    List<String> parameterNames();
    Object invoke(Object[] args) throws Exception;
}
