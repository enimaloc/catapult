package fr.enimaloc.catapult.common.dto.twitchat;

/** Why payload {@code index} was refused: {@code field} is a data path, or action / mode / dataJson. */
public record TwitchatValidationError(int index, String field, String message) {
}
