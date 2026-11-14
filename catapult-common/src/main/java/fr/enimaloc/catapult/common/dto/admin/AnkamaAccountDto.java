package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/**
 * An Ankama bot account as the admin API shows it: never the HAAPI key, the refresh token or the Shield certificate.
 * {@code displayTag}: the bot's {@code nickname#tag}. {@code shieldCodeDomain}: where the last Shield code was sent
 * (the e-mail domain), null when none is waiting.
 */
public record AnkamaAccountDto(UUID id, String displayTag, String authState, String sessionState, int fillOrder,
                               boolean friendLimitReached, boolean enabled, long friendCount,
                               String shieldCodeDomain) {}
