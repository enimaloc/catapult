package fr.enimaloc.catapult.common.dto.channel;

/** The stream title card: whether Catapult sets the title, and the channel-wide template (blank: none). */
public record TitleSettingsRequest(boolean enabled, String template) {}
