package fr.enimaloc.catapult.service.twitch;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** An extension the broadcaster installed, as Helix's {@code GET /users/extensions/list} lists it. */
public record TwitchExtension(
        @JsonProperty("can_activate") boolean canActivate,
        String id,
        String name,
        @JsonProperty("type") List<Type> types,
        String version
) {
    public TwitchExtension {
        types = types == null ? List.of() : List.copyOf(types);
    }

    public enum Type {
        @JsonProperty("component") COMPONENT,
        @JsonProperty("mobile") MOBILE,
        @JsonProperty("overlay") OVERLAY,
        @JsonProperty("panel") PANEL
    }
}
