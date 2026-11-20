package fr.enimaloc.catapult.common.dto.debug;

import java.time.Instant;
import java.util.List;

/**
 * A batch of the OBS and Twitchat exchanges a member's browser saw while the member is in debug mode, relayed by
 * catapult-web to catapult-api's debug journal.
 *
 * @param source  where it was captured: {@code page} (the member's page) or {@code widget} (the OBS widget)
 * @param sentAt  the browser's clock when it sent the batch, from which the server derives the clock skew
 * @param entries the exchanges, oldest first
 */
public record ClientExchangeBatch(String source, Instant sentAt, List<ClientExchangeEntry> entries) {}
