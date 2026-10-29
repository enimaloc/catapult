package fr.enimaloc.catapult.simulation.twitch;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import fr.enimaloc.catapult.simulation.CallKey;
import fr.enimaloc.catapult.simulation.CallSetting;
import fr.enimaloc.catapult.simulation.SimulatedCallRunner;
import fr.enimaloc.catapult.simulation.SimulationJournal;
import fr.enimaloc.catapult.simulation.SimulationProperties;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real registry, runner and journal over mocked repositories, with a fictitious account ({@code mock-alice}) and a
 * real one ({@code 12345}, holding a Twitch token), both active with their bot enabled.
 */
public class SimulationFixture {

    public final UserAccountRepository accounts = mock(UserAccountRepository.class);
    public final OAuthTokenRepository tokens = mock(OAuthTokenRepository.class);
    public final MinecraftFriendLinkRepository minecraftLinks = mock(MinecraftFriendLinkRepository.class);
    public final MinecraftServiceAccountRepository minecraftServiceAccounts =
            mock(MinecraftServiceAccountRepository.class);
    public final MockEnvironment env = new MockEnvironment();
    public final UserAccount fictitious = account("mock-alice");
    public final UserAccount real = account("12345");
    public final OAuthToken realToken = new OAuthToken();
    public final SimulationRegistry registry;
    public final SimulationJournal journal = new SimulationJournal();
    public final SimulatedCallRunner runner;

    public SimulationFixture(Path dir) {
        when(accounts.findById(fictitious.getId())).thenReturn(Optional.of(fictitious));
        when(accounts.findById(real.getId())).thenReturn(Optional.of(real));
        when(tokens.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());
        when(tokens.findByUserAndProvider(eq(real), eq(OAuthToken.Provider.TWITCH))).thenReturn(Optional.of(realToken));
        SimulationProperties properties = new SimulationProperties();
        properties.setStateFile(dir.resolve("simulation.json").toString());
        registry = new SimulationRegistry(properties, accounts, tokens, minecraftLinks, minecraftServiceAccounts, env);
        runner = new SimulatedCallRunner(registry, journal);
    }

    private static UserAccount account(String twitchId) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId(twitchId);
        account.setTwitchUsername(twitchId);
        return account;
    }

    public void set(UserAccount account, CallKey key, CallSetting setting) {
        registry.set(account == null ? null : account.getId(), key, setting);
    }

    /** The journaled keys, oldest first. */
    public List<String> journaledKeys() {
        return journal.recent(null).stream().map(SimulationJournal.Entry::key).toList();
    }

    @SuppressWarnings("unchecked")
    public static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
