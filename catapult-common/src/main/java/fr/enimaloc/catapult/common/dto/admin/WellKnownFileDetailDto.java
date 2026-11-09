package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.List;

/** A configured well-known file with its status, headers and content, for its edit dialog. */
public record WellKnownFileDetailDto(long id, WellKnownLocation location, String path, int status,
                                     List<WellKnownHeader> headers, String contentType, String content,
                                     Instant updatedAt) {}
