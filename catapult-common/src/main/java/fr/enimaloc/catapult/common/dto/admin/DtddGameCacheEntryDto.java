package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;

/** A cached DTDD title, as DTDD's search found it; {@code stale} past the cache's lifetime. */
public record DtddGameCacheEntryDto(long dtddId, String name, String mediaType, String posterUrl, Instant fetchedAt,
                                    boolean stale) {}
