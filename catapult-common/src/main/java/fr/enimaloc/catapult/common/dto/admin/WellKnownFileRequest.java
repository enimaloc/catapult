package fr.enimaloc.catapult.common.dto.admin;

/** A well-known file to create, or to replace an existing one with. */
public record WellKnownFileRequest(WellKnownLocation location, String path, String contentType, String content) {}
