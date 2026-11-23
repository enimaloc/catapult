package fr.enimaloc.catapult.common.dto.channel;

/**
 * The owner's FACEIT settings, for the settings card: the nickname they declared (null for none), its FACEIT profile
 * page, and whether the FACEIT source can serve stats on this instance ({@code available}: enabled, with a key in
 * the shared pool). No secret is ever held for a member.
 */
public record FaceitData(
        String nickname,
        String profileUrl,
        boolean available
) {}
