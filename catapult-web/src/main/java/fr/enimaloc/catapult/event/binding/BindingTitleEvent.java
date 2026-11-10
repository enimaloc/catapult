package fr.enimaloc.catapult.event.binding;

import fr.enimaloc.catapult.event.ChannelUpdatedEvent;

/** A game's own stream title; empty when it uses the channel-wide one (a text field's value, never null). */
public record BindingTitleEvent(String username, String bindingId, String title) implements ChannelUpdatedEvent {
}
