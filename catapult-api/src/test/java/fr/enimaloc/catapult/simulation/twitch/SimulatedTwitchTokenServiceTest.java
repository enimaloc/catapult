package fr.enimaloc.catapult.simulation.twitch;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedCalls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SimulatedTwitchTokenServiceTest {

    @TempDir
    Path dir;

    SimulationFixture fx;
    RestClient restClient = mock(RestClient.class);
    TokenEncryptionService encryption = mock(TokenEncryptionService.class);
    SimulatedTwitchTokenService tokens;
    SimulatedSystemTwitchAccountService systemBot;

    @BeforeEach
    void setUp() {
        fx = new SimulationFixture(dir);
        tokens = new SimulatedTwitchTokenService(fx.tokens, encryption, restClient, fx.runner);
        systemBot = new SimulatedSystemTwitchAccountService(fx.accounts, fx.tokens, encryption, restClient,
                fx.runner);
        fx.realToken.setAccessToken("encrypted-access");
        fx.realToken.setRefreshToken("encrypted-refresh");
        when(encryption.decrypt("encrypted-access")).thenReturn("current-access");
    }

    @Test
    void an_ok_refresh_keeps_the_current_token_and_calls_nothing() {
        assertThat(tokens.refreshAccessToken(fx.realToken, fx.real)).isEqualTo("current-access");
        assertThat(tokens.refreshAccessToken(null, fx.fictitious)).isNotBlank();

        assertThat(fx.journaledKeys()).containsExactly("twitch.helix.refresh-token", "twitch.helix.refresh-token");
        verifyNoInteractions(restClient);
    }

    @Test
    void a_failed_refresh_answers_null_like_the_real_one() {
        fx.set(fx.fictitious, SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN, CallSetting.error(400));

        assertThat(tokens.refreshAccessToken(null, fx.fictitious)).isNull();
        verifyNoInteractions(restClient);
    }

    @Test
    void the_app_token_follows_its_global_setting() {
        assertThat(tokens.getAppAccessToken()).isNotBlank();

        fx.set(null, SimulatedCalls.TWITCH_APP_APP_TOKEN, CallSetting.error(500));

        assertThatThrownBy(tokens::getAppAccessToken).isInstanceOf(HttpServerErrorException.class);
        assertThat(fx.journal.recent(null)).allSatisfy(entry -> assertThat(entry.accountId()).isNull());
        verifyNoInteractions(restClient);
    }

    @Test
    void the_system_bot_refresh_goes_through_its_global_key() {
        UserAccount bot = new UserAccount();
        bot.setTwitchId("999");
        bot.setSystemAccount(true);
        OAuthToken botToken = new OAuthToken();
        botToken.setAccessToken("encrypted-access");
        botToken.setRefreshToken("encrypted-refresh");
        when(fx.accounts.findBySystemAccountTrue()).thenReturn(Optional.of(bot));
        when(fx.tokens.findByUserAndProvider(bot, OAuthToken.Provider.TWITCH)).thenReturn(Optional.of(botToken));

        assertThat(systemBot.refresh()).contains("current-access");

        fx.set(null, SimulatedCalls.TWITCH_APP_BOT_REFRESH, CallSetting.error(401));

        assertThat(systemBot.refresh()).isEmpty();
        assertThat(fx.journaledKeys()).containsExactly("twitch.app.bot-refresh", "twitch.app.bot-refresh");
        verifyNoInteractions(restClient);
    }

    @Test
    void a_simulated_bot_mod_status_answers_modded_and_calls_nothing() {
        // A real bot refresh no longer lets the check out: only the account's own key does
        fx.env.setProperty("twitch.client-id", "id");
        fx.env.setProperty("twitch.client-secret", "secret");
        fx.set(null, SimulatedCalls.TWITCH_APP_BOT_REFRESH, CallSetting.real());

        assertThat(systemBot.check(fx.real, "streamer-token").modded()).isTrue();
        assertThat(systemBot.check(fx.fictitious, "").modded()).isTrue();

        assertThat(fx.journaledKeys()).containsExactly("twitch.helix.bot-mod-status", "twitch.helix.bot-mod-status");
        verifyNoInteractions(restClient);
    }

    @Test
    void a_failed_simulated_bot_mod_status_answers_not_modded() {
        fx.set(fx.real, SimulatedCalls.TWITCH_HELIX_BOT_MOD_STATUS, CallSetting.error(401));

        assertThat(systemBot.check(fx.real, "").modded()).isFalse();
        verifyNoInteractions(restClient);
    }

    @Test
    void the_bot_mod_status_goes_out_only_when_real_for_the_account() {
        UserAccount bot = new UserAccount();
        bot.setTwitchId("999");
        when(fx.accounts.findBySystemAccountTrue()).thenReturn(Optional.of(bot));
        fx.set(fx.real, SimulatedCalls.TWITCH_HELIX_BOT_MOD_STATUS, CallSetting.real());

        // The mocked client answers nothing: the real check falls back to not modded
        assertThat(systemBot.check(fx.real, "streamer-token").modded()).isFalse();

        verify(restClient).get();
        assertThat(fx.journal.recent(fx.real.getId())).singleElement()
                .satisfies(entry -> assertThat(entry.mode()).isEqualTo("REAL"));
    }

    @Test
    void a_fictitious_account_never_has_its_bot_mod_status_real() {
        assertThatThrownBy(() -> fx.set(fx.fictitious, SimulatedCalls.TWITCH_HELIX_BOT_MOD_STATUS,
                CallSetting.real())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_unlinked_system_bot_refreshes_nothing() {
        when(fx.accounts.findBySystemAccountTrue()).thenReturn(Optional.empty());

        assertThat(systemBot.refresh()).isEmpty();
        assertThat(fx.journaledKeys()).isEmpty();
    }
}
