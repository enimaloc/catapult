package fr.enimaloc.catapult.dto.integration;

import java.util.List;
import java.util.Objects;

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

    /**
     * An mdui-select; {@code source} (JSON {@code {"stable":…,"beta":…}}, or null) names the
     * Twitchat requests that fill its options at runtime, rendered as data-source. An option's
     * {@code icon} (a picture URL, or null) shows beside it, and beside the select once picked.
     */
    record Select(Attrs attrs, Message text, String value, List<Option> options, String source)
            implements IntegrationNode {
        /** {@code protocols}: the Twitchat branches that have it (data-protocols), null when all do. */
        public record Option(String value, Message text, String icon, List<String> protocols) {
            public Option {
                protocols = protocols == null ? null : List.copyOf(protocols);
            }

            public Option(String value, Message text) {
                this(value, text, null, null);
            }
        }

        /** Whether any option has a picture: the select then shows the picked one's. */
        public boolean hasIcons() {
            return options.stream().anyMatch(option -> option.icon() != null);
        }

        /** Whether some option exists on some Twitchat branches only: those are hidden on the others. */
        public boolean hasBranchOptions() {
            return options.stream().anyMatch(option -> option.protocols() != null);
        }

        /** The picked option's picture, null without one. */
        public String pickedIcon() {
            return options.stream().filter(option -> option.value().equals(value)).map(Option::icon)
                    .filter(Objects::nonNull).findFirst().orElse(null);
        }

        public Select {
            options = List.copyOf(options);
        }

        public String type() {
            return "select";
        }
    }

    record Button(Attrs attrs, Kind kind, Message text) implements IntegrationNode {
        public enum Kind { SEGMENTED, FILLED, TEXT }

        public String type() {
            return switch (kind) {
                case SEGMENTED -> "segmented-button";
                case FILLED -> "button";
                case TEXT -> "text-button";
            };
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

    /** An mdui-text-field labelled {@code text}; {@code multiline} grows with its content. */
    record TextField(Attrs attrs, Message text, String inputType, Object value, boolean multiline) implements IntegrationNode {
        public String type() {
            return "text-field";
        }
    }

    /** A hidden input: values the page's JS reads back (a payload's action, mode, names). */
    record Hidden(Attrs attrs, String value) implements IntegrationNode {
        public String type() {
            return "hidden";
        }
    }

    record Collapse(Attrs attrs, List<Item> items) implements IntegrationNode {
        /** One mdui-collapse-item: {@code text} its header, {@code value} its collapse value. */
        public record Item(Attrs attrs, String value, Message text, List<IntegrationNode> children) {
            public Item {
                children = List.copyOf(children);
            }
        }

        public Collapse {
            items = List.copyOf(items);
        }

        public String type() {
            return "collapse";
        }
    }

    /**
     * A list of rows edited in place (a chat message's buttons): {@code template} is the row
     * "add" clones; each row's fields are paths relative to the row.
     */
    record Repeater(Attrs attrs, Message text, List<List<IntegrationNode>> rows, List<IntegrationNode> template)
            implements IntegrationNode {
        public Repeater {
            rows = rows.stream().map(List::copyOf).toList();
            template = List.copyOf(template);
        }

        public String type() {
            return "repeater";
        }
    }
}
