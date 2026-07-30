package fr.enimaloc.catapult.chat.command.js;

/**
 * Thrown when a command AST cannot be safely compiled to JavaScript, e.g. because a
 * user-controlled value would need to be spliced into a raw (non-string-literal) position in
 * the generated source and does not have a safe shape for that position.
 */
public class JsCompilationException extends RuntimeException {

    public JsCompilationException(String message) {
        super(message);
    }
}
