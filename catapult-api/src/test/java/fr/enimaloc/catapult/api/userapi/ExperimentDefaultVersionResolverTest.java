package fr.enimaloc.catapult.api.userapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.experiment.ApiDefaultVersionExperiment;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The default API version a request without an explicit one gets: the variant of the default version experiment. */
class ExperimentDefaultVersionResolverTest {

    private static final String KEY = ApiDefaultVersionExperiment.KEY;

    private final ExperimentService experiments = mock(ExperimentService.class);
    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T12:00:00Z"));
    private final ExperimentDefaultVersionResolver resolver =
            new ExperimentDefaultVersionResolver(experiments, accounts, clock);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me/game");

    private UserAccount member;

    @BeforeEach
    void setUp() {
        member = new UserAccount();
        member.setId(UUID.randomUUID());
        when(accounts.findById(member.getId())).thenReturn(Optional.of(member));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                id, null, List.of(new SimpleGrantedAuthority("API_TOKEN"))));
    }

    @Test
    void member_inV3Variant_getsV3() {
        authenticateAs(member.getId());
        when(experiments.getVariantKey(member, KEY)).thenReturn(Optional.of(ApiDefaultVersionExperiment.V3_VARIANT));

        assertThat(resolver.resolveVersion(request)).isEqualTo("3");
    }

    @Test
    void member_inV2Variant_getsV2() {
        authenticateAs(member.getId());
        when(experiments.getVariantKey(member, KEY)).thenReturn(Optional.of(ApiDefaultVersionExperiment.V2_VARIANT));

        assertThat(resolver.resolveVersion(request)).isEqualTo("2");
    }

    @Test
    void member_outsideTheExperiment_getsV2() {
        authenticateAs(member.getId());
        when(experiments.getVariantKey(member, KEY)).thenReturn(Optional.empty());

        assertThat(resolver.resolveVersion(request)).isEqualTo("2");
    }

    @Test
    void anonymous_followsTheHeaviestVariant() {
        when(experiments.getHeaviestVariantKey(KEY)).thenReturn(Optional.of(ApiDefaultVersionExperiment.V2_VARIANT));
        assertThat(resolver.resolveVersion(request)).isEqualTo("2");

        ExperimentDefaultVersionResolver swapped = new ExperimentDefaultVersionResolver(experiments, accounts, clock);
        when(experiments.getHeaviestVariantKey(KEY)).thenReturn(Optional.of(ApiDefaultVersionExperiment.V3_VARIANT));
        assertThat(swapped.resolveVersion(request)).isEqualTo("3");
    }

    @Test
    void anonymousToken_isAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        when(experiments.getHeaviestVariantKey(KEY)).thenReturn(Optional.of(ApiDefaultVersionExperiment.V3_VARIANT));

        assertThat(resolver.resolveVersion(request)).isEqualTo("3");
        verify(experiments, never()).getVariantKey(any(), any());
    }

    @Test
    void anonymous_withoutAnActiveExperiment_getsV2() {
        when(experiments.getHeaviestVariantKey(KEY)).thenReturn(Optional.empty());

        assertThat(resolver.resolveVersion(request)).isEqualTo("2");
    }

    @Test
    void unknownMember_isAnonymous() {
        UUID ghost = UUID.randomUUID();
        authenticateAs(ghost);
        when(accounts.findById(ghost)).thenReturn(Optional.empty());
        when(experiments.getHeaviestVariantKey(KEY)).thenReturn(Optional.of(ApiDefaultVersionExperiment.V3_VARIANT));

        assertThat(resolver.resolveVersion(request)).isEqualTo("3");
    }

    @Test
    void member_isCachedFor60Seconds() {
        authenticateAs(member.getId());
        when(experiments.getVariantKey(member, KEY)).thenReturn(Optional.of(ApiDefaultVersionExperiment.V3_VARIANT));
        resolver.resolveVersion(request);

        when(experiments.getVariantKey(member, KEY)).thenReturn(Optional.of(ApiDefaultVersionExperiment.V2_VARIANT));
        clock.advance(Duration.ofSeconds(59));
        assertThat(resolver.resolveVersion(request)).isEqualTo("3");

        clock.advance(Duration.ofSeconds(2));
        assertThat(resolver.resolveVersion(request)).isEqualTo("2");
        verify(experiments, times(2)).getVariantKey(member, KEY);
    }

    @Test
    void anonymous_isCachedFor60Seconds() {
        when(experiments.getHeaviestVariantKey(KEY)).thenReturn(Optional.of(ApiDefaultVersionExperiment.V2_VARIANT));
        resolver.resolveVersion(request);

        when(experiments.getHeaviestVariantKey(KEY)).thenReturn(Optional.of(ApiDefaultVersionExperiment.V3_VARIANT));
        clock.advance(Duration.ofSeconds(59));
        assertThat(resolver.resolveVersion(request)).isEqualTo("2");

        clock.advance(Duration.ofSeconds(2));
        assertThat(resolver.resolveVersion(request)).isEqualTo("3");
        verify(experiments, times(2)).getHeaviestVariantKey(KEY);
    }

    @Test
    void failure_fallsBackToTheConfiguredDefault_withoutCaching() {
        when(experiments.getHeaviestVariantKey(KEY)).thenThrow(new IllegalStateException("database down"));

        assertThat(resolver.resolveVersion(request)).isNull();
        assertThat(resolver.resolveVersion(request)).isNull();
        verify(experiments, times(2)).getHeaviestVariantKey(KEY);
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
