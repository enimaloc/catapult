package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/** A configured well-known file with its content, for its edit dialog. */
public record WellKnownFileDetailDto(long id, WellKnownLocation location, String path, String contentType,
                                     String content, Instant updatedAt) {}
