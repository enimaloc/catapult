package fr.enimaloc.catapult.common.dto.channel;

import java.util.List;

/**
 * The Twitch extensions of a channel: every one the broadcaster installed, active or not, then the active ones, slot
 * by slot. Both lists are empty when Twitch cannot be read (no token, Twitch failing).
 */
public record TwitchExtensionsDto(List<Installed> installed, List<Active> active) {

    public enum Type { COMPONENT, MOBILE, OVERLAY, PANEL }

    /** {@code canActivate}: whether Twitch lets the broadcaster activate it (configured, approved…). */
    public record Installed(String id, String name, String version, List<Type> types, boolean canActivate) {}

    /** One active slot, {@code slot} numbered from 1 within its {@code type}; {@code x}/{@code y} only for components. */
    public record Active(Type type, int slot, String id, String name, String version, Integer x, Integer y) {}
}
