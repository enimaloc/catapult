package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/** A well-known file, with its status and headers, to create or to replace an existing one with (no status: 200; no headers: none). */
public record WellKnownFileRequest(WellKnownLocation location, String path, Integer status, List<WellKnownHeader> headers,
                                   String contentType, String content) {}
