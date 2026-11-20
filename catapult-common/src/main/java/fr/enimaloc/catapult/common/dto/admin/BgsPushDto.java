package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/**
 * A call the server sent the bot after an admin's free call. {@code service} and {@code method} are the bgs.desc
 * names, else the hash ({@code 0xBCA72135}) and the id; {@code text} is null when the body does not decode.
 */
public record BgsPushDto(Instant at, String service, String method, String text, String hex) {}
