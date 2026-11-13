package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/**
 * A Battle.net bot account as the admin API shows it: never the e-mail, the password or the browser state.
 * {@code waitingForCode}: an admin login waits for the 2FA code now (a {@code CODE_REQUIRED} left by an automatic
 * login has none: only a re-authentication can take a code then).
 */
public record BattleNetAccountDto(UUID id, String label, String battleTag, String authState, String sessionState,
                                  int fillOrder, boolean friendLimitReached, boolean enabled, long friendCount,
                                  boolean waitingForCode) {}
