package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DebugEnableRequest;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeDto;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto;
import fr.enimaloc.catapult.domain.debug.DebugExchange;
import fr.enimaloc.catapult.service.debug.MemberDebugAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import fr.enimaloc.catapult.api.ApiPaths;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping(ApiPaths.ADMIN + "/members/{id}/debug")
@RequiredArgsConstructor
public class ApiAdminMemberDebugController {

    static final int DEFAULT_PAGE_SIZE = 50;

    private final MemberDebugAdmin debugAdmin;

    /** 409 when already on; no body: secrets masked, every page replayed. The JWT is required: it names the admin who switched the mode on. */
    @PostMapping("/enable")
    public MemberDebugStateDto enable(@PathVariable UUID id, @RequestBody(required = false) DebugEnableRequest body,
                                      @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        DebugEnableRequest request = body != null ? body : new DebugEnableRequest(false, DebugEnableRequest.DEFAULT_REPLAY_RATE);
        return debugAdmin.enable(id, request.revealSecrets(), request.replayRate(), () -> UUID.fromString(jwt.getSubject()));
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable UUID id) {
        debugAdmin.disable(id);
    }

    /** Deletes everything recorded for the member. */
    @DeleteMapping("/exchanges")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable UUID id) {
        debugAdmin.clear(id);
    }

    /** 404 when the mode was never switched on for the member. */
    @GetMapping
    public MemberDebugStateDto state(@PathVariable UUID id) {
        return debugAdmin.state(id);
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
        return debugAdmin.exchanges(id, service, dir, errorsOnly, from, to, page, size);
    }

    /** 404 when the exchange is another member's. */
    @GetMapping("/exchanges/{exchangeId}")
    public DebugExchangeDto exchange(@PathVariable UUID id, @PathVariable UUID exchangeId) {
        return debugAdmin.exchange(id, exchangeId);
    }

    /** The services the member has exchanges with, for the filter. */
    @GetMapping("/services")
    public List<String> services(@PathVariable UUID id) {
        return debugAdmin.services(id);
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
}
