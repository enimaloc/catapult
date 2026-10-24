package fr.enimaloc.catapult.domain.debug;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One HTTP exchange (incoming or outgoing) recorded for a member in debug mode. */
@Entity
@Table(name = "debug_exchange")
@Getter
@Setter
public class DebugExchange {

    public enum Direction { IN, OUT }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Direction direction;

    @Column(nullable = false, length = 32)
    private String service;

    @Column(length = 128)
    private String operation;

    @Column(nullable = false, length = 16)
    private String method;

    @Column(nullable = false)
    private String url;

    @Column(name = "request_headers")
    private String requestHeaders;

    @Column(name = "request_body")
    private String requestBody;

    /** Null when no response came back (transport error). */
    private Integer status;

    @Column(name = "response_headers")
    private String responseHeaders;

    @Column(name = "response_body")
    private String responseBody;

    private String error;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    /** true: a grouped call, only this member's share is recorded (request included). */
    @Column(nullable = false)
    private boolean filtered;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    public boolean isError() {
        return error != null || status == null || status >= 400;
    }
}
