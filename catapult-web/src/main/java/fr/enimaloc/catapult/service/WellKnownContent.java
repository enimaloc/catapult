package fr.enimaloc.catapult.service;

/** A well-known file as catapult-api served it: its status (200, 404; 502 unreachable), type and bytes. */
public record WellKnownContent(int status, String contentType, byte[] body) {}
