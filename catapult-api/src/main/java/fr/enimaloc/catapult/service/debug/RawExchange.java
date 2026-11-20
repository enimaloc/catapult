package fr.enimaloc.catapult.service.debug;

import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.time.Instant;

/**
 * One outgoing or incoming HTTP exchange, as observed before redaction and truncation.
 *
 * @param requestRaw  the wire bytes of the request when it is binary (protobuf frames), or null
 * @param responseRaw the wire bytes of the response when it is binary, or null
 */
public record RawExchange(Instant at, String method, URI uri, HttpHeaders requestHeaders, byte[] requestBody,
                          Integer status, HttpHeaders responseHeaders, byte[] responseBody, String error,
                          long durationMs, byte[] requestRaw, byte[] responseRaw) {

    /** An exchange without raw bytes. */
    public RawExchange(Instant at, String method, URI uri, HttpHeaders requestHeaders, byte[] requestBody,
                       Integer status, HttpHeaders responseHeaders, byte[] responseBody, String error,
                       long durationMs) {
        this(at, method, uri, requestHeaders, requestBody, status, responseHeaders, responseBody, error,
                durationMs, null, null);
    }
}
