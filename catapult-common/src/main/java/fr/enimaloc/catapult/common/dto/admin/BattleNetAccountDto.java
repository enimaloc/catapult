package fr.enimaloc.catapult.common.dto.admin;

import java.util.UUID;

/** A Battle.net bot account as the admin API shows it: never the e-mail, the password or the browser state. */
public record BattleNetAccountDto(UUID id, String label, String battleTag, String authState, String sessionState,
                                  int fillOrder, boolean friendLimitReached, boolean enabled, long friendCount) {}
