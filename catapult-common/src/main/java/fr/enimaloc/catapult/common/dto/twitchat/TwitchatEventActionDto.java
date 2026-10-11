package fr.enimaloc.catapult.common.dto.twitchat;

/** One payload of a Catapult event, as the owner configured it; its position is its index in the list. */
public record TwitchatEventActionDto(String action, String mode, String dataJson, boolean enabled) {
}
