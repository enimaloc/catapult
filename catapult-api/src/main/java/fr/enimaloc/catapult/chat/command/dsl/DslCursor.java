package fr.enimaloc.catapult.chat.command.dsl;

public class DslCursor {
    private final String text;
    private int pos;

    public DslCursor(String text) {
        this.text = text;
    }

    public boolean atEnd() {
        return pos >= text.length();
    }

    public char peek() {
        return text.charAt(pos);
    }

    public char next() {
        return text.charAt(pos++);
    }

    public int position() {
        return pos;
    }

    public void position(int p) {
        pos = p;
    }

    public String substring(int from, int to) {
        return text.substring(from, to);
    }

    public int length() {
        return text.length();
    }
}
