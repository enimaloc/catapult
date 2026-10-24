package fr.enimaloc.catapult.service.debug;

import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.time.Instant;

/** One outgoing or incoming HTTP exchange, as observed before redaction and truncation. */
public record RawExchange(Instant at, String method, URI uri, HttpHeaders requestHeaders, byte[] requestBody,
                          Integer status, HttpHeaders responseHeaders, byte[] responseBody, String error,
                          long durationMs) {
}
