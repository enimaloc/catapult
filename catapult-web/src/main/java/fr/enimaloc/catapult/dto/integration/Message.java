package fr.enimaloc.catapult.dto.integration;

import java.util.List;

/**
 * Text a node displays: a message key (with its arguments) resolved in the visitor's locale by
 * the template, or a literal shown as-is (a Minecraft name, a Twitchat branch, ...).
 */
public record Message(String key, List<Object> args, String literal) {

    public Message {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static Message key(String key, Object... args) {
        return new Message(key, List.of(args), null);
    }

    public static Message literal(String text) {
        return new Message(null, List.of(), text);
    }

    public boolean isKey() {
        return key != null;
    }

    /** For {@code #messages.msgWithParams}, which takes an array. */
    public Object[] argsArray() {
        return args.toArray();
    }
}
