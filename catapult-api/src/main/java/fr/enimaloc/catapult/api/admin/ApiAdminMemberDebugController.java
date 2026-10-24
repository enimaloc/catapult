package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DebugEnableRequest;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeDto;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto;
import fr.enimaloc.catapult.domain.debug.DebugExchange;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.repository.debug.DebugExchangeSpecs;
import fr.enimaloc.catapult.service.debug.MemberDebugService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** An admin's switch for a member's debug mode, and the browsing of the exchanges it recorded. */
@RestController
@RequestMapping("/api/admin/members/{id}/debug")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminMemberDebugController {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private final MemberDebugService debugService;
    private final DebugExchangeRepository exchanges;
    private final UserAccountRepository users;

    /** 409 when already on. The JWT is required: it names the admin who switched the mode on. */
    @PostMapping("/enable")
    public MemberDebugStateDto enable(@PathVariable UUID id, @RequestBody(required = false) DebugEnableRequest body,
                                      @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        requireMember(id);
        UUID adminId = UUID.fromString(jwt.getSubject());
        debugService.enable(id, body != null && body.revealSecrets(), adminId);
        return state(id);
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable UUID id) {
        requireMember(id);
        debugService.disable(id);
    }

    /** Deletes everything recorded for the member. */
    @PostMapping("/clear")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable UUID id) {
        requireMember(id);
        debugService.clear(id);
    }

    /** 404 when the mode was never switched on for the member. */
    @GetMapping
    public MemberDebugStateDto state(@PathVariable UUID id) {
        return debugService.stateDto(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * The member's exchanges, newest first. {@code direction} is IN or OUT, {@code outcome} error or success
     * (error: what {@link DebugExchange#isError()} says); {@code from} and {@code to} bound the time inclusively.
     */
    @GetMapping("/exchanges")
    public AdminPage<DebugExchangeSummaryDto> exchanges(
            @PathVariable UUID id,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        DebugExchange.Direction dir = parseDirection(direction);
        Boolean errorsOnly = parseOutcome(outcome);
        PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "occurredAt"));
        Page<DebugExchange> found = exchanges.findAll(DebugExchangeSpecs.matching(id,
                service == null || service.isBlank() ? null : service, dir, errorsOnly, from, to), request);
        return new AdminPage<>(found.getContent().stream().map(ApiAdminMemberDebugController::summary).toList(),
                found.getNumber(), found.getTotalPages(), found.getTotalElements());
    }

    /** 404 when the exchange is another member's. */
    @GetMapping("/exchanges/{exchangeId}")
    public DebugExchangeDto exchange(@PathVariable UUID id, @PathVariable UUID exchangeId) {
        DebugExchange e = exchanges.findByIdAndUserId(exchangeId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new DebugExchangeDto(e.getId(), e.getOccurredAt(), e.getDirection().name(), e.getService(),
                e.getOperation(), e.getMethod(), e.getUrl(), e.getStatus(), e.isError(), e.getDurationMs(),
                e.isFiltered(), e.getRequestHeaders(), e.getRequestBody(), e.getResponseHeaders(),
                e.getResponseBody(), e.getError(), e.getTraceId());
    }

    /** The services the member has exchanges with, for the filter. */
    @GetMapping("/services")
    public List<String> services(@PathVariable UUID id) {
        return exchanges.findServicesByUserId(id);
    }

    private void requireMember(UUID id) {
        if (!users.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static DebugExchange.Direction parseDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return null;
        }
        try {
            return DebugExchange.Direction.valueOf(direction.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown direction");
        }
    }

    /** true: errors only, false: successes only, null: both. */
    private static Boolean parseOutcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return null;
        }
        return switch (outcome.trim().toLowerCase(Locale.ROOT)) {
            case "error" -> true;
            case "success" -> false;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown outcome");
        };
    }

    private static DebugExchangeSummaryDto summary(DebugExchange e) {
        return new DebugExchangeSummaryDto(e.getId(), e.getOccurredAt(), e.getDirection().name(), e.getService(),
                e.getOperation(), e.getMethod(), e.getUrl(), e.getStatus(), e.isError(), e.getDurationMs(),
                e.isFiltered());
    }
}
