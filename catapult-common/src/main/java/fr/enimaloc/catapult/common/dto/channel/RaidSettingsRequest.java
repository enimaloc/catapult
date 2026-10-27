package fr.enimaloc.catapult.common.dto.channel;

/** The raid settings: how recent (1..120 minutes) a game is put back as the category on an outgoing raid; null or 0: off. */
public record RaidSettingsRequest(Integer restoreGameMinutes) {}
