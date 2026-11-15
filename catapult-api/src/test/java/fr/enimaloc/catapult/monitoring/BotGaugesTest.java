package fr.enimaloc.catapult.monitoring;

import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import fr.enimaloc.catapult.service.ankama.AnkamaSessionManager;
import fr.enimaloc.catapult.service.battlenet.BattleNetSessionManager;
import fr.enimaloc.catapult.service.epic.EpicSessionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Battle.net, Ankama and Epic bot pools as gauges: bots by login state, open sessions, streamer links by status. */
class BotGaugesTest {

    private final BattleNetServiceAccountRepository battleNetAccounts = mock(BattleNetServiceAccountRepository.class);
    private final BattleNetFriendLinkRepository battleNetLinks = mock(BattleNetFriendLinkRepository.class);
    private final AnkamaServiceAccountRepository ankamaAccounts = mock(AnkamaServiceAccountRepository.class);
    private final AnkamaFriendLinkRepository ankamaLinks = mock(AnkamaFriendLinkRepository.class);
    private final EpicServiceAccountRepository epicAccounts = mock(EpicServiceAccountRepository.class);
    private final EpicFriendLinkRepository epicLinks = mock(EpicFriendLinkRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private BotGauges gauges(BattleNetSessionManager battleNet, AnkamaSessionManager ankama) {
        return gauges(battleNet, ankama, null);
    }

    private BotGauges gauges(BattleNetSessionManager battleNet, AnkamaSessionManager ankama,
                             EpicSessionManager epic) {
        return new BotGauges(battleNetAccounts, battleNetLinks, ankamaAccounts, ankamaLinks, epicAccounts, epicLinks,
                provider(battleNet), provider(ankama), provider(epic));
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    @Test
    void botsByLoginState_perPlatform() {
        when(battleNetAccounts.countByEnabledTrueAndAuthState(BattleNetServiceAccount.AuthState.READY)).thenReturn(2L);
        when(ankamaAccounts.countByEnabledTrueAndAuthState(AnkamaServiceAccount.AuthState.CODE_REQUIRED)).thenReturn(1L);
        gauges(null, null).bindTo(registry);

        assertThat(gauge("catapult.bots.accounts", "platform", "battlenet", "auth_state", "READY")).isEqualTo(2);
        assertThat(gauge("catapult.bots.accounts", "platform", "battlenet", "auth_state", "AUTH_FAILED")).isZero();
        assertThat(gauge("catapult.bots.accounts", "platform", "ankama", "auth_state", "CODE_REQUIRED")).isEqualTo(1);
        assertThat(gauge("catapult.bots.accounts", "platform", "ankama", "auth_state", "REAUTH_REQUIRED")).isZero();
    }

    @Test
    void streamerLinksByStatus_perPlatform() {
        when(battleNetLinks.countByStatus(BattleNetFriendLink.Status.ACCEPTED)).thenReturn(3L);
        when(ankamaLinks.countByStatus(AnkamaFriendLink.Status.PENDING)).thenReturn(4L);
        gauges(null, null).bindTo(registry);

        assertThat(gauge("catapult.bots.links", "platform", "battlenet", "status", "ACCEPTED")).isEqualTo(3);
        assertThat(gauge("catapult.bots.links", "platform", "ankama", "status", "PENDING")).isEqualTo(4);
        assertThat(gauge("catapult.bots.links", "platform", "ankama", "status", "REMOVED")).isZero();
    }

    @Test
    void openSessions_fromTheSessionManagers_zeroWhenAPlatformIsOff() {
        BattleNetSessionManager battleNet = mock(BattleNetSessionManager.class);
        when(battleNet.readySessionCount()).thenReturn(2L);
        gauges(battleNet, null).bindTo(registry);

        assertThat(gauge("catapult.bots.sessions.ready", "platform", "battlenet")).isEqualTo(2);
        assertThat(gauge("catapult.bots.sessions.ready", "platform", "ankama")).isZero();
    }

    @Test
    void epicBots_links_andSessions_areTaggedEpic() {
        when(epicAccounts.countByEnabledTrueAndAuthState(EpicServiceAccount.AuthState.REAUTH_REQUIRED)).thenReturn(1L);
        when(epicLinks.countByStatus(EpicFriendLink.Status.INVITE_REJECTED)).thenReturn(5L);
        EpicSessionManager epic = mock(EpicSessionManager.class);
        when(epic.readySessionCount()).thenReturn(3L);
        gauges(null, null, epic).bindTo(registry);

        assertThat(gauge("catapult.bots.accounts", "platform", "epic", "auth_state", "REAUTH_REQUIRED")).isEqualTo(1);
        assertThat(gauge("catapult.bots.accounts", "platform", "epic", "auth_state", "READY")).isZero();
        assertThat(gauge("catapult.bots.links", "platform", "epic", "status", "INVITE_REJECTED")).isEqualTo(5);
        assertThat(gauge("catapult.bots.links", "platform", "epic", "status", "ACCEPTED")).isZero();
        assertThat(gauge("catapult.bots.sessions.ready", "platform", "epic")).isEqualTo(3);
    }

    @Test
    void epicSessions_areZero_whenEpicIsOff() {
        gauges(null, null, null).bindTo(registry);

        assertThat(gauge("catapult.bots.sessions.ready", "platform", "epic")).isZero();
    }
}
