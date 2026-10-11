package fr.enimaloc.catapult.dto.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The DTO form of {@code spa:if} + {@code spa:on}/{@code spa:switch} + {@code spa:value}, rendered
 * by {@code spa:state="${...}"} (see StateAttributeProcessor): the element is hidden unless every
 * flag starts true, and visibility.js then re-evaluates each flag from its SSE triggers.
 *
 * <p>Triggers all go out as {@code data-on}: visibility.js resolves {@code data-on} and
 * {@code data-switch} with the same grammar, so {@code Event.field=VALUE} works in either.
 */
public record SpaState(List<Flag> flags, List<Sync> syncs) {

    /** A named visibility flag, its server-side value, and the SSE specs that update it. */
    public record Flag(String name, boolean initial, List<String> triggers) {
        public Flag {
            requireToken(name);
            triggers.forEach(SpaState::requireToken);
            triggers = List.copyOf(triggers);
        }
    }

    /** A DOM property (textContent, checked, ...) assigned from SSE specs, like spa:value. */
    public record Sync(String property, List<String> specs) {
        public Sync {
            requireToken(property);
            specs.forEach(SpaState::requireToken);
            specs = List.copyOf(specs);
        }
    }

    public SpaState {
        flags = List.copyOf(flags);
        syncs = List.copyOf(syncs);
    }

    public static SpaState flag(String name, boolean initial, String... triggers) {
        return new SpaState(List.of(), List.of()).and(name, initial, triggers);
    }

    public static SpaState sync(String property, String... specs) {
        return new SpaState(List.of(), List.of()).andSync(property, specs);
    }

    public SpaState and(String name, boolean initial, String... triggers) {
        List<Flag> next = new ArrayList<>(flags);
        next.add(new Flag(name, initial, List.of(triggers)));
        return new SpaState(next, syncs);
    }

    public SpaState andSync(String property, String... specs) {
        List<Sync> next = new ArrayList<>(syncs);
        next.add(new Sync(property, List.of(specs)));
        return new SpaState(flags, next);
    }

    public boolean visible() {
        return flags.stream().allMatch(Flag::initial);
    }

    /** {@code data-if}, or null when there is no flag (Thymeleaf then drops the attribute). */
    public String dataIf() {
        return joinOrNull(flags.stream().map(Flag::name));
    }

    public String dataOn() {
        return joinOrNull(flags.stream()
                .flatMap(flag -> flag.triggers().stream().map(trigger -> flag.name() + ":" + trigger)));
    }

    public String dataValue() {
        return joinOrNull(syncs.stream()
                .flatMap(sync -> sync.specs().stream().map(spec -> sync.property() + ":" + spec)));
    }

    private static String joinOrNull(Stream<String> entries) {
        String joined = entries.collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    /** visibility.js splits entries on ',' and name from spec on the first ':'. */
    private static void requireToken(String token) {
        if (token == null || token.isBlank() || token.contains(",")) {
            throw new IllegalArgumentException("Invalid spa:state token \"" + token + "\": must be non-blank, without ','");
        }
    }
}
