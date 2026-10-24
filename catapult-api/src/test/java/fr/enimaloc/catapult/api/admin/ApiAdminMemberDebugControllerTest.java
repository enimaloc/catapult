package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AdminPage;
import fr.enimaloc.catapult.common.dto.admin.DebugEnableRequest;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeDto;
import fr.enimaloc.catapult.common.dto.admin.DebugExchangeSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.MemberDebugStateDto;
import fr.enimaloc.catapult.domain.debug.DebugExchange;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.debug.DebugExchangeRepository;
import fr.enimaloc.catapult.service.debug.MemberDebugService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The admin debug endpoints called directly, over a real (H2) exchange table so the filters are exercised. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:debugctl;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ApiAdminMemberDebugControllerTest {

    @Autowired DebugExchangeRepository exchangeRepo;
    @Autowired TestEntityManager em;

    private final MemberDebugService service = mock(MemberDebugService.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private ApiAdminMemberDebugController controller;
    private final UUID member = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new ApiAdminMemberDebugController(service, exchangeRepo, users);
        when(users.existsById(any())).thenReturn(true);
    }

    private static Instant at(int minute) {
        return Instant.parse("2026-10-01T10:00:00Z").plusSeconds(60L * minute);
    }

    private DebugExchange exchange(UUID user, int minute, DebugExchange.Direction direction, String service,
                                   Integer status, String error) {
        DebugExchange e = new DebugExchange();
        e.setUserId(user);
        e.setOccurredAt(at(minute));
        e.setDirection(direction);
        e.setService(service);
        e.setMethod("GET");
        e.setUrl("https://" + service + "/x");
        e.setStatus(status);
        e.setError(error);
        e.setDurationMs(12);
        e.setRequestHeaders("h1");
        e.setResponseBody("body");
        e.setTraceId("trace-" + minute);
        return em.persistAndFlush(e);
    }

    private static Jwt jwt(UUID sub) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(sub.toString());
        return jwt;
    }

    private static void assertStatus(Runnable call, HttpStatus expected) {
        assertThatThrownBy(call::run).isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode().value()).isEqualTo(expected.value());
    }

    @Test
    void enable_withoutJwt_isRejected() {
        assertStatus(() -> controller.enable(member, new DebugEnableRequest(false), null), HttpStatus.UNAUTHORIZED);

        verify(service, never()).enable(any(), anyBoolean(), any());
    }

    @Test
    void enable_recordsTheCallingAdmin_andAnswersTheState() {
        MemberDebugStateDto state = new MemberDebugStateDto(true, true, at(0), admin, null, null, 0);
        when(service.stateDto(member)).thenReturn(Optional.of(state));

        assertThat(controller.enable(member, new DebugEnableRequest(true), jwt(admin))).isEqualTo(state);

        verify(service).enable(member, true, admin);
    }

    @Test
    void enable_whenActive_conflicts() {
        when(service.enable(any(), anyBoolean(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));

        assertStatus(() -> controller.enable(member, new DebugEnableRequest(false), jwt(admin)), HttpStatus.CONFLICT);
    }

    @Test
    void enable_ofAnUnknownMember_is404() {
        when(users.existsById(member)).thenReturn(false);

        assertStatus(() -> controller.enable(member, new DebugEnableRequest(false), jwt(admin)), HttpStatus.NOT_FOUND);
    }

    @Test
    void state_ofAMemberNeverDebugged_is404() {
        when(service.stateDto(member)).thenReturn(Optional.empty());

        assertStatus(() -> controller.state(member), HttpStatus.NOT_FOUND);
    }

    @Test
    void disableAndClear_delegate() {
        controller.disable(member);
        controller.clear(member);

        verify(service).disable(member);
        verify(service).clear(member);
    }

    @Test
    void exchanges_filterByServiceDirectionOutcomeAndPeriod() {
        UUID other = UUID.randomUUID();
        exchange(member, 1, DebugExchange.Direction.OUT, "twitch", 200, null);
        DebugExchange failed = exchange(member, 2, DebugExchange.Direction.OUT, "twitch", 500, null);
        DebugExchange transport = exchange(member, 3, DebugExchange.Direction.OUT, "steam", null, null);
        DebugExchange withError = exchange(member, 4, DebugExchange.Direction.IN, "steam", 200, "boom");
        DebugExchange notFound = exchange(member, 5, DebugExchange.Direction.IN, "twitch", 404, null);
        DebugExchange ok = exchange(member, 6, DebugExchange.Direction.IN, "twitch", 399, null);
        exchange(other, 3, DebugExchange.Direction.OUT, "twitch", 500, null);

        AdminPage<DebugExchangeSummaryDto> all = controller.exchanges(member, null, null, null, null, null, 0, 50);
        assertThat(all.content()).extracting(DebugExchangeSummaryDto::occurredAt)
                .containsExactly(at(6), at(5), at(4), at(3), at(2), at(1));
        assertThat(all.totalElements()).isEqualTo(6);

        assertThat(ids(controller.exchanges(member, null, null, "error", null, null, 0, 50)))
                .containsExactly(notFound.getId(), withError.getId(), transport.getId(), failed.getId());
        assertThat(ids(controller.exchanges(member, null, null, "success", null, null, 0, 50)))
                .containsExactly(ok.getId(), exchangeId(1));
        assertThat(ids(controller.exchanges(member, "steam", "OUT", null, null, null, 0, 50)))
                .containsExactly(transport.getId());
        assertThat(ids(controller.exchanges(member, "twitch", "in", "error", at(5), at(6), 0, 50)))
                .containsExactly(notFound.getId());
        assertThat(ids(controller.exchanges(member, null, null, null, at(2), at(3), 0, 50)))
                .containsExactly(transport.getId(), failed.getId());
    }

    private java.util.UUID exchangeId(int minute) {
        return exchangeRepo.findAll().stream().filter(e -> e.getUserId().equals(member)
                && e.getOccurredAt().equals(at(minute))).findFirst().orElseThrow().getId();
    }

    private static java.util.List<UUID> ids(AdminPage<DebugExchangeSummaryDto> page) {
        return page.content().stream().map(DebugExchangeSummaryDto::id).toList();
    }

    @Test
    void exchanges_summaryCarriesTheErrorFlag_andPagesAreCapped() {
        exchange(member, 1, DebugExchange.Direction.OUT, "twitch", 503, null);

        DebugExchangeSummaryDto dto = controller.exchanges(member, null, null, null, null, null, 0, 5000)
                .content().getFirst();

        assertThat(dto.error()).isTrue();
        assertThat(dto.status()).isEqualTo(503);
        assertThat(dto.direction()).isEqualTo("OUT");
        assertThat(controller.exchanges(member, null, null, null, null, null, 0, 5000).totalPages()).isEqualTo(1);
        for (int i = 2; i <= 205; i++) {
            exchange(member, i, DebugExchange.Direction.OUT, "twitch", 200, null);
        }
        assertThat(controller.exchanges(member, null, null, null, null, null, 0, 5000).content()).hasSize(200);
        assertThat(controller.exchanges(member, null, null, null, null, null, 0, 0).content()).hasSize(1);
    }

    @Test
    void exchanges_withAnUnknownDirectionOrOutcome_is400() {
        assertStatus(() -> controller.exchanges(member, null, "sideways", null, null, null, 0, 50), HttpStatus.BAD_REQUEST);
        assertStatus(() -> controller.exchanges(member, null, null, "meh", null, null, 0, 50), HttpStatus.BAD_REQUEST);
    }

    @Test
    void exchange_returnsTheFullRecord() {
        DebugExchange e = exchange(member, 1, DebugExchange.Direction.OUT, "twitch", 500, "x");

        DebugExchangeDto dto = controller.exchange(member, e.getId());

        assertThat(dto.traceId()).isEqualTo("trace-1");
        assertThat(dto.requestHeaders()).isEqualTo("h1");
        assertThat(dto.responseBody()).isEqualTo("body");
        assertThat(dto.errorMessage()).isEqualTo("x");
        assertThat(dto.error()).isTrue();
    }

    @Test
    void exchange_ofAnotherMember_is404() {
        DebugExchange e = exchange(UUID.randomUUID(), 1, DebugExchange.Direction.OUT, "twitch", 200, null);

        assertStatus(() -> controller.exchange(member, e.getId()), HttpStatus.NOT_FOUND);
        assertStatus(() -> controller.exchange(member, UUID.randomUUID()), HttpStatus.NOT_FOUND);
    }

    @Test
    void services_listsTheDistinctServicesOfTheMemberOnly() {
        exchange(member, 1, DebugExchange.Direction.OUT, "twitch", 200, null);
        exchange(member, 2, DebugExchange.Direction.OUT, "steam", 200, null);
        exchange(member, 3, DebugExchange.Direction.IN, "twitch", 200, null);
        exchange(UUID.randomUUID(), 4, DebugExchange.Direction.OUT, "igdb", 200, null);

        assertThat(controller.services(member)).containsExactly("steam", "twitch");
    }
}
