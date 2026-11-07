package fr.enimaloc.catapult.common.dto.channel;

/**
 * One slot of a configuration's extension layout, {@code slot} numbered from 1 within its {@code type}.
 * A null {@code extensionId} means the slot is emptied; a slot absent from the layout is left as it is.
 */
public record ExtensionSlotDto(TwitchExtensionsDto.Type type, int slot, String extensionId, String extensionName) {}
