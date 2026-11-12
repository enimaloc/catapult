package fr.enimaloc.catapult.common.dto.admin;

/**
 * What a shared API key was used for, as the admin Keys tab shows it live: the calls Catapult sent with it in the
 * provider's short window ({@code windowSeconds}: Steam's 5 s, DTDD's minute) out of {@code windowLimit}, and in its
 * longer {@code period} (DAY: Steam's daily limit, MONTH: DTDD's) out of {@code periodLimit}; {@code *Remaining}:
 * what the provider said was left, null when it doesn't say. Counted by the catapult-api instance that answered.
 */
public record KeyUsageDto(int windowCalls, Integer windowLimit, int windowSeconds, Integer windowRemaining,
                          String period, long periodCalls, Long periodLimit, Integer periodRemaining) {}
