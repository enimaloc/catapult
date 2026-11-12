package fr.enimaloc.catapult.common.dto.admin;

import java.time.Instant;
import java.util.List;

/** A cached DTDD search: its query (lower-cased, trimmed) and the titles DTDD found; {@code stale} past its lifetime. */
public record DtddSearchCacheEntryDto(String query, List<Long> dtddIds, Instant searchedAt, boolean stale) {}
