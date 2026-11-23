package fr.enimaloc.catapult.common.dto.channel;

/**
 * The owner's Hypixel settings, for the settings card: the Minecraft account they declared (its name as shown when
 * declared and its UUID, 32 hex digits; both null for none), and whether the Hypixel source can serve stats on this
 * instance ({@code available}: enabled, with a key in the shared pool). No secret is ever held for a member.
 */
public record HypixelData(
        String name,
        String uuid,
        boolean available
) {}
