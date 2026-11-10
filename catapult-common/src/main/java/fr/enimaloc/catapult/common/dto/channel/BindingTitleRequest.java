package fr.enimaloc.catapult.common.dto.channel;

/** A game's own stream title; blank or null: back to the channel-wide one. */
public record BindingTitleRequest(String title) {}
