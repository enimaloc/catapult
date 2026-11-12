package fr.enimaloc.catapult.common.dto.admin;

import java.util.List;

/**
 * The DTDD caches at a glance: each one's size and how many are past its own lifetime (the {@code *TtlHours}), the
 * pooled keys' quotas, the reserve of its month the bulk refetch leaves each key, and that refetch.
 */
public record DtddCachesSummaryDto(long topics, long topicsStale, long games, long gamesStale, long searches,
                                   long searchesStale, int topicsTtlHours, int gamesTtlHours, int searchesTtlHours,
                                   List<DtddKeyQuotaDto> quotas, int monthlyReserve, DtddRefetchJobDto job) {}
