package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** A well-known file, with its status and headers, to create or to replace an existing one with. */
public record WellKnownFileRequest(WellKnownLocation location, String path, int status, List<WellKnownHeader> headers,
                                   String contentType, String content) {}
