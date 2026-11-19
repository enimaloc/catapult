package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.common.dto.emergency.ChannelEmergencyDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyReportDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStatusDto;
import fr.enimaloc.catapult.common.dto.emergency.EmergencyStopDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.emergency.EmergencyReport;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop.Scope;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.emergency.EmergencyReportRepository;
import fr.enimaloc.catapult.repository.emergency.ProviderEmergencyStopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The emergency stops and reports as the admin, the channel and the status banner read them. Built inside a
 * transaction (the channel of a stop or a report is lazy); the public status never carries a channel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyViews {

    static final int PAGE_SIZE = 50;
    static final int HISTORY_SIZE = 100;

    private final ProviderEmergencyStopRepository stops;
    private final EmergencyReportRepository reports;
    private final EmergencyVodResolver vodResolver;
    private final EmergencyStopService stopService;
    private final GetterConfigRepository getterConfigs;

    /** The active stops, or the lifted and expired ones (the latest of them) for the history. */
    @Transactional(readOnly = true)
    public List<EmergencyStopDto> stops(boolean active) {
        Instant now = Instant.now();
        List<ProviderEmergencyStop> found = active
                ? stops.findActiveWithChannel(now)
                : stops.findInactiveWithChannel(now, PageRequest.of(0, HISTORY_SIZE));
        return found.stream().map(s -> toDto(s, s.getChannel() == null ? null : s.getChannel().getTwitchUsername()))
                .toList();
    }

    /** One page of reports, newest first; the VOD links are the ones already resolved, nothing is asked of Twitch. */
    @Transactional(readOnly = true)
    public List<EmergencyReportDto> reports(int page) {
        return reports.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(page, 0), PAGE_SIZE)).stream()
                .map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ChannelEmergencyDto channel(UserAccount channel) {
        Set<String> used = getterConfigs.findByUserOrderByPriorityAsc(channel).stream()
                .filter(GetterConfig::isEnabled).map(c -> c.getProvider().name()).collect(Collectors.toSet());
        List<ProviderEmergencyStop> active = stopService.activeStopsFor(channel);
        return new ChannelEmergencyDto(channel.isBotEnabled(),
                active.stream().filter(s -> s.getScope() == Scope.CHANNEL)
                        .map(s -> toDto(s, channel.getTwitchUsername())).toList(),
                active.stream().filter(s -> s.getScope() == Scope.GLOBAL
                                && ("ALL".equals(s.getProvider()) || used.contains(s.getProvider())))
                        .map(s -> toDto(s, null)).toList());
    }

    /** Only the global stops: a local one would name a channel. */
    public EmergencyStatusDto status() {
        return new EmergencyStatusDto(stopService.activeStops().stream()
                .filter(s -> s.getScope() == Scope.GLOBAL).map(s -> toDto(s, null)).toList());
    }

    private EmergencyReportDto toDto(EmergencyReport report) {
        String vodUrl = null;
        try {
            vodUrl = vodResolver.vodUrl(report).orElse(null);
        } catch (Exception e) {
            log.warn("[Emergency] Could not build the VOD link of report {}: {}", report.getId(), e.getMessage());
        }
        return new EmergencyReportDto(report.getId(), report.getChannel().getTwitchUsername(),
                report.getProvider().name(), report.getReportedByLogin(), report.getCreatedAt(),
                report.getWithdrawnAt() != null, vodUrl, report.getContext());
    }

    private static EmergencyStopDto toDto(ProviderEmergencyStop stop, String channelLogin) {
        return new EmergencyStopDto(stop.getId(), stop.getProvider(), stop.getScope().name(),
                stop.getOrigin().name(), channelLogin, stop.getCreatedAt(), stop.getExpiresAt(),
                stop.getLiftedAt(), stop.getLiftedBy());
    }
}
