package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/**
 * What an admin's free BGS call got. {@code status}: the BGS status (0 = OK), null when nobody answers the method or
 * the call failed otherwise ({@code error}: the session closed, a timeout). {@code replyText} is null when the reply
 * does not decode; {@code replyHex} is always there.
 */
public record BgsCallResult(Integer status, long durationMs, String replyText, String replyHex,
                            List<BgsPushDto> pushes, String error) {}
