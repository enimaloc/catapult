package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/**
 * A Battle.net bot account as the admin API shows it: never the e-mail, the password or the browser state.
 * {@code waitingForCode}: an admin login waits for the 2FA code now (a {@code CODE_REQUIRED} left by an automatic
 * login has none: only a re-authentication can take a code then). {@code displayName}: the BattleTag, else (before
 * the first login) the masked e-mail, e.g. {@code b***@example.com}.
 */
public record BattleNetAccountDto(UUID id, String displayName, String battleTag, String authState, String sessionState,
                                  int fillOrder, boolean friendLimitReached, boolean enabled, long friendCount,
                                  boolean waitingForCode) {}
