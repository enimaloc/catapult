package fr.enimaloc.catapult.common.dto.debug;

import java.time.Instant;
import java.util.Map;

/**
 * One OBS or Twitchat exchange seen by the browser.
 *
 * @param service    {@code obs} or {@code twitchat}
 * @param direction  {@code OUT} (sent by Catapult) or {@code IN} (emitted by OBS or Twitchat)
 * @param operation  the request, event or action type
 * @param at         the browser's clock when the exchange started (a request's sending)
 * @param durationMs how long the request waited for its answer, or null
 * @param status     the OBS request status code ({@code 100} is a success), or null
 * @param request    the message sent, or null
 * @param response   the answer received, or null
 * @param error      what went wrong (the OBS comment, {@code timeout}, {@code connection closed}), or null
 */
public record ClientExchangeEntry(String service, String direction, String operation, Instant at, Long durationMs,
                                  Integer status, Map<String, Object> request, Map<String, Object> response,
                                  String error) {}
