package fr.enimaloc.catapult.dto.integration;

import java.util.List;

/**
 * One element of an integration card, rendered by integrations.html's {@code node} fragment,
 * which switches on {@link #type()}. {@link #text()} is the node's main text (content, label or
 * placeholder), resolved once by that fragment.
 */
public sealed interface IntegrationNode {

    String type();

    Attrs attrs();

    default Message text() {
        return null;
    }

    /** A {@code <span>}, or a {@code <p>} when {@code paragraph}. */
    record Text(Attrs attrs, boolean paragraph, Message text) implements IntegrationNode {
        public String type() {
            return paragraph ? "paragraph" : "span";
        }
    }

    record Chip(Attrs attrs, Message text) implements IntegrationNode {
        public String type() {
            return "chip";
        }
    }

    /** A native input; {@code text} is its placeholder, {@code ariaLabel} defaults to it. */
    record Input(Attrs attrs, String inputType, Message text, Message ariaLabel, Object value,
                 Integer min, Integer max) implements IntegrationNode {
        public Input {
            ariaLabel = ariaLabel == null ? text : ariaLabel;
        }

        public String type() {
            return "input";
        }
    }

    /** An mdui-switch inside a {@code <label>} with {@code text} beside it; attrs go on the switch. */
    record Switch(Attrs attrs, boolean checked, Message text) implements IntegrationNode {
        public String type() {
            return "switch";
        }
    }

    record Select(Attrs attrs, Message text, String value, List<Option> options) implements IntegrationNode {
        public record Option(String value, Message text) {}

        public Select {
            options = List.copyOf(options);
        }

        public String type() {
            return "select";
        }
    }

    record Button(Attrs attrs, Kind kind, Message text) implements IntegrationNode {
        public enum Kind { SEGMENTED, FILLED }

        public String type() {
            return kind == Kind.SEGMENTED ? "segmented-button" : "button";
        }
    }

    record Container(Attrs attrs, Kind kind, List<IntegrationNode> children) implements IntegrationNode {
        public enum Kind { DIV, SEGMENTED_GROUP }

        public Container {
            children = List.copyOf(children);
        }

        public String type() {
            return kind == Kind.DIV ? "div" : "segmented-group";
        }
    }

    /** Escape hatch for markup no node covers: inserted unescaped, so never user input. */
    record Raw(String html) implements IntegrationNode {
        public String type() {
            return "raw";
        }

        public Attrs attrs() {
            return Attrs.NONE;
        }
    }
}
