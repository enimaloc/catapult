package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** How catapult-web must answer for a configured file: catapult-api describes it rather than replaying it. */
public record WellKnownResponseDto(int status, String contentType, List<WellKnownHeader> headers, String content) {}
