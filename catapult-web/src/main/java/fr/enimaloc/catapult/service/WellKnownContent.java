package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.common.dto.admin.WellKnownResponseDto;

/** catapult-api's answer for a well-known path: its status (200, 404; 502 unreachable) and, for 200, the response to replay. */
public record WellKnownContent(int status, WellKnownResponseDto response) {}
