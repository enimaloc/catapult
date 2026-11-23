package fr.enimaloc.catapult.common.dto.channel;

/**
 * The owner's Riot settings, for the settings card: the Riot ID they declared ({@code gameName#tagLine}, null for
 * none) and the League of Legends platform it plays on ({@code euw1}…), and whether the Riot source can serve stats on
 * this instance ({@code available}: enabled, with a key in the shared pool). No secret is ever held for a member.
 */
public record RiotData(
        String riotId,
        String platform,
        boolean available
) {}
