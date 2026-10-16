package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/** A configured well-known file as listed, with its status (its headers and content left out; size in UTF-8 bytes). */
public record WellKnownFileDto(long id, WellKnownLocation location, String path, int status, String contentType,
                               int size, Instant updatedAt) {}
