package fr.enimaloc.catapult.event;

/** A provider ({@code TWITCH}, {@code STEAM}, {@code XBOX}...) was connected to or disconnected from the channel. */
public record ChannelConnectionChangedEvent(String username, String provider, boolean connected) implements ChannelUpdatedEvent {
}
