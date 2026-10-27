package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeDto;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto;
import fr.enimaloc.catapult.domain.debug.DebugExchange;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.repository.debug.DebugExchangeSpecs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** An admin's switch for a member's debug mode, and the browsing of the exchanges it recorded. */
@Service
@RequiredArgsConstructor
public class MemberDebugAdmin {

    static final int MAX_PAGE_SIZE = 200;

    private final MemberDebugService debugService;
    private final DebugExchangeRepository exchanges;
    private final UserAccountRepository users;

    /** 409 when already on. The admin is only named once the member is known. */
    public MemberDebugStateDto enable(UUID id, boolean revealSecrets, double replayRate, Supplier<UUID> adminId) {
        requireMember(id);
        debugService.enable(id, revealSecrets, replayRate, adminId.get());
        return state(id);
    }

    public void disable(UUID id) {
        requireMember(id);
        debugService.disable(id);
    }

    /** Deletes everything recorded for the member. */
    public void clear(UUID id) {
        requireMember(id);
        debugService.clear(id);
    }

    /** 404 when the mode was never switched on for the member. */
    public MemberDebugStateDto state(UUID id) {
        return debugService.stateDto(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The member's exchanges, newest first; {@code errorsOnly} null keeps both outcomes. */
    public AdminPage<DebugExchangeSummaryDto> exchanges(UUID id, String service, DebugExchange.Direction direction,
                                                        Boolean errorsOnly, Instant from, Instant to,
                                                        int page, int size) {
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "occurredAt"));
        Page<DebugExchange> found = exchanges.findAll(DebugExchangeSpecs.matching(id,
                service == null || service.isBlank() ? null : service, direction, errorsOnly, from, to), request);
        return new AdminPage<>(found.getContent().stream().map(MemberDebugAdmin::summary).toList(),
                found.getNumber(), found.getTotalPages(), found.getTotalElements());
    }

    /** 404 when the exchange is another member's. */
    public DebugExchangeDto exchange(UUID id, UUID exchangeId) {
        DebugExchange e = exchanges.findByIdAndUserId(exchangeId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new DebugExchangeDto(e.getId(), e.getOccurredAt(), e.getDirection().name(), e.getService(),
                e.getOperation(), e.getMethod(), e.getUrl(), e.getStatus(), e.isError(), e.getDurationMs(),
                e.isFiltered(), e.getRequestHeaders(), e.getRequestBody(), e.getResponseHeaders(),
                e.getResponseBody(), e.getError(), e.getTraceId());
    }

    /** The services the member has exchanges with, for the filter. */
    public List<String> services(UUID id) {
        return exchanges.findServicesByUserId(id);
    }

    private void requireMember(UUID id) {
        if (!users.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static DebugExchangeSummaryDto summary(DebugExchange e) {
        return new DebugExchangeSummaryDto(e.getId(), e.getOccurredAt(), e.getDirection().name(), e.getService(),
                e.getOperation(), e.getMethod(), e.getUrl(), e.getStatus(), e.isError(), e.getDurationMs(),
                e.isFiltered());
    }
}
