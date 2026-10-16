package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/** A configured well-known file as listed (its content left out; size in UTF-8 bytes). */
public record WellKnownFileDto(long id, WellKnownLocation location, String path, String contentType,
                               int size, Instant updatedAt) {}
