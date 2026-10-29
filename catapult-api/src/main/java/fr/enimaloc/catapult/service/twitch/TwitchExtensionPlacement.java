package fr.enimaloc.catapult.service.twitch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Arrays;
import java.util.List;

/**
 * The extensions active on the channel, slot by slot, as Helix's {@code GET/PUT /users/extensions} reads and writes
 * them: the slots are keyed {@code "1"}, {@code "2"}…, an empty one answered {@code {"active": false}}.
 * <p>
 * A {@code null} slot or placement is left out of the PUT body, so Twitch keeps it as it is: only the slots given
 * change (see {@link #update()}). {@link Slot#empty()} deactivates a slot.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TwitchExtensionPlacement(
        ComponentPlacement component,
        OverlayPlacement overlay,
        PanelPlacement panel
) {
    /** Every slot inactive: what a read answers when the channel's extensions cannot be read. */
    public static TwitchExtensionPlacement empty() {
        return new TwitchExtensionPlacement(ComponentPlacement.empty(), OverlayPlacement.empty(), PanelPlacement.empty());
    }

    /** A partial update: the slots it is not given stay untouched on Twitch. */
    public static Update update() {
        return new Update();
    }

    /** One slot; {@code name} is only read, {@code x}/{@code y} only exist for components. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Slot(boolean active, String id, String name, String version, Integer x, Integer y) {
        /** Activates the extension {@code id} at {@code version} in a panel or overlay slot. */
        public static Slot of(String id, String version) {
            return new Slot(true, id, null, version, null, null);
        }

        /** Activates the extension {@code id} at {@code version} in a component slot, at ({@code x}, {@code y}). */
        public static Slot at(String id, String version, int x, int y) {
            return new Slot(true, id, null, version, x, y);
        }

        /** Deactivates whatever extension the slot holds, whatever its kind. */
        public static Slot empty() {
            return new Slot(false, null, null, null, null, null);
        }
    }

    /** Not named {@code getSlots}: Jackson would write it out as a property of the PUT body. */
    public sealed interface Placement permits ComponentPlacement, OverlayPlacement, PanelPlacement {
        /** The slots in order, {@code null} where one is not given. */
        List<Slot> slots();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ComponentPlacement(@JsonProperty("1") Slot first, @JsonProperty("2") Slot second)
            implements Placement {
        @Override
        public List<Slot> slots() {
            return Arrays.asList(first, second);
        }

        public static ComponentPlacement empty() {
            return new ComponentPlacement(Slot.empty(), Slot.empty());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OverlayPlacement(@JsonProperty("1") Slot first) implements Placement {
        @Override
        public List<Slot> slots() {
            return Arrays.asList(first);
        }

        public static OverlayPlacement empty() {
            return new OverlayPlacement(Slot.empty());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PanelPlacement(@JsonProperty("1") Slot first, @JsonProperty("2") Slot second,
                                 @JsonProperty("3") Slot third) implements Placement {
        @Override
        public List<Slot> slots() {
            return Arrays.asList(first, second, third);
        }

        public static PanelPlacement empty() {
            return new PanelPlacement(Slot.empty(), Slot.empty(), Slot.empty());
        }
    }

    /** Builds a placement holding only the slots it is given, numbered from 1 as Twitch does. */
    public static final class Update {
        private final Slot[] components = new Slot[2];
        private final Slot[] overlays = new Slot[1];
        private final Slot[] panels = new Slot[3];

        private Update() {}

        public Update component(int number, Slot slot) {
            return set(components, number, slot);
        }

        public Update overlay(int number, Slot slot) {
            return set(overlays, number, slot);
        }

        public Update panel(int number, Slot slot) {
            return set(panels, number, slot);
        }

        public TwitchExtensionPlacement build() {
            return new TwitchExtensionPlacement(
                    allNull(components) ? null : new ComponentPlacement(components[0], components[1]),
                    allNull(overlays) ? null : new OverlayPlacement(overlays[0]),
                    allNull(panels) ? null : new PanelPlacement(panels[0], panels[1], panels[2]));
        }

        private Update set(Slot[] slots, int number, Slot slot) {
            if (number < 1 || number > slots.length) {
                throw new IllegalArgumentException("slot " + number + " out of 1.." + slots.length);
            }
            slots[number - 1] = slot;
            return this;
        }

        private static boolean allNull(Slot[] slots) {
            return Arrays.stream(slots).allMatch(slot -> slot == null);
        }
    }
}
