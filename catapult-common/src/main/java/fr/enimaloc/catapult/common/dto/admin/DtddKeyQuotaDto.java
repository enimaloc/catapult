package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/**
 * What DTDD said was left of a pooled key's quota with its last answer ({@code maskedKey} as the keys tab shows it):
 * null counts when it never said; {@code at} when it said it; {@code blockedUntil} while the key rests (rate limit).
 */
public record DtddKeyQuotaDto(String keyId, String maskedKey, Integer remainingMinute, Integer remainingMonth,
                              Instant at, Instant blockedUntil) {}
