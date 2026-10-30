package fr.enimaloc.catapult.simulation.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2UserService;
import fr.enimaloc.catapult.service.account.AccountService;
import fr.enimaloc.catapult.simulation.SimulationGate;
import fr.enimaloc.catapult.simulation.SimulationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The {@code mock} profile's fictitious accounts: real {@link UserAccount}s of the dev database, without any token,
 * whose Twitch id ({@code m} and a short suffix) no real Twitch id can be. They are made and removed through the
 * paths a member's account takes ({@link CatapultOAuth2UserService#createNewAccount},
 * {@link AccountService#deleteAccountImmediately}); what only the simulation knows about them (their admin role, the
 * channels they moderate) is kept in the {@link SimulationRegistry}.
 */
@Slf4j
@Service
@Profile("mock")
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
@DependsOn("mockGuard")
public class FictitiousAccountService {

    /**
     * A fictitious Steam id: 17 digits, as a SteamID64 is, starting with this prefix. With its 7 digits it stays in
     * 76561197900000000..76561197909999999, below the first individual SteamID64 (76561197960265728): no real
     * account can have it.
     */
    static final String STEAM_ID_PREFIX = "7656119790";
    private static final int STEAM_ID_SUFFIX_DIGITS = 7;
    private static final int STEAM_ID_ATTEMPTS = 20;
    private static final int TWITCH_ID_SUFFIX_LENGTH = 8;

    private final UserAccountRepository accounts;
    private final CatapultOAuth2UserService members;
    private final GetterConfigRepository getterConfigs;
    private final AccountService accountService;
    private final SimulationRegistry registry;
    private final ApplicationEventPublisher eventPublisher;

    public FictitiousAccountService(UserAccountRepository accounts, CatapultOAuth2UserService members,
                                    GetterConfigRepository getterConfigs, AccountService accountService,
                                    SimulationRegistry registry, ApplicationEventPublisher eventPublisher) {
        this.accounts = accounts;
        this.members = members;
        this.getterConfigs = getterConfigs;
        this.accountService = accountService;
        this.registry = registry;
        this.eventPublisher = eventPublisher;
    }

    /**
     * A new fictitious member, active, set up as a new member is, with its Steam getter enabled. Publishes the
     * {@link AccountCreatedEvent} a new member's account does.
     *
     * @param identity   its username (unique), and optionally its Twitch id (a made-up one when blank), avatar and
     *                   Steam id (a fictitious one when blank)
     * @param admin      whether its fictitious login is given {@code ROLE_ADMIN}
     * @param moderates  the accounts whose channels it moderates
     * @param botEnabled whether its bot is on
     * @throws IllegalArgumentException for a blank or taken username, Twitch id or Steam id, or an unknown moderated
     *                                  account
     */
    @Transactional
    public UserAccount create(FictitiousIdentity identity, boolean admin, List<UUID> moderates, boolean botEnabled) {
        String login = freeUsername(identity.username(), null);
        String twitchId = isBlank(identity.twitchId())
                ? SimulationRegistry.FICTITIOUS_PREFIX + shortSuffix()
                : fictitiousTwitchId(identity.twitchId());
        if (accounts.findByTwitchId(twitchId).isPresent()) {
            throw new IllegalArgumentException("The Twitch id " + twitchId + " is taken");
        }
        String steamId = isBlank(identity.steamId()) ? freeSteamId() : freeSteamId(identity.steamId(), null);
        List<UUID> moderatedIds = moderates == null ? List.of() : List.copyOf(new LinkedHashSet<>(moderates));
        List<UserAccount> moderated = accounts.findAllById(moderatedIds);
        if (moderated.size() != moderatedIds.size()) {
            throw new IllegalArgumentException("Unknown moderated account in " + moderatedIds);
        }

        UserAccount account = members.createNewAccount(twitchId, login);
        account.setStatus(UserAccount.Status.ACTIVE);
        account.setBotEnabled(botEnabled);
        account.setSteamId(steamId);
        account.setProfileImageUrl(blankToNull(identity.profileImageUrl()));
        account = accounts.save(account);
        enableSteamGetter(account);

        List<String> moderatedChannelIds = moderated.stream().map(UserAccount::getTwitchId).toList();
        registry.update(account.getId(), data -> data.withModeratedChannelIds(moderatedChannelIds));
        registry.setAdmin(account.getId(), admin);

        eventPublisher.publishEvent(new AccountCreatedEvent(this, account));
        log.info("Fictitious account {} ({}) created, admin={}, bot={}", account.getId(), login, admin, botEnabled);
        return account;
    }

    /**
     * Changes what a fictitious account is known by: a {@code null} field is left as it is, a blank avatar is
     * cleared. Its Twitch id stays: the simulation's state and a converted account's way back are keyed on it.
     *
     * @throws IllegalArgumentException for an unknown or real account, a Twitch id, a blank username or Steam id, or
     *                                  one another account has
     */
    @Transactional
    public UserAccount updateIdentity(UUID accountId, FictitiousIdentity identity) {
        UserAccount account = fictitious(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No fictitious account " + accountId));
        if (identity.twitchId() != null) {
            throw new IllegalArgumentException("A fictitious account's Twitch id cannot be changed");
        }
        if (identity.username() != null) {
            account.setTwitchUsername(freeUsername(identity.username(), accountId));
        }
        if (identity.profileImageUrl() != null) {
            account.setProfileImageUrl(blankToNull(identity.profileImageUrl()));
        }
        if (identity.steamId() != null) {
            account.setSteamId(freeSteamId(identity.steamId(), accountId));
        }
        return accounts.save(account);
    }

    /**
     * Deletes a fictitious account and its data as an account deletion does, then what the simulation kept about it.
     *
     * @throws IllegalArgumentException for an unknown or real account
     */
    @Transactional
    public void delete(UUID accountId) {
        UserAccount account = fictitious(accountId)
                .orElseThrow(() -> new IllegalArgumentException("No fictitious account " + accountId));
        accountService.deleteAccountImmediately(account);
        registry.forget(accountId);
        log.info("Fictitious account {} deleted", accountId);
    }

    /** Every account but the system one, real and fictitious, by username. */
    public List<UserAccount> list() {
        return accounts.findAll().stream()
                .filter(account -> !account.isSystemAccount())
                .sorted(Comparator.comparing(UserAccount::getTwitchUsername,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /** The account {@code accountId}, real or fictitious, unless it is the system one. */
    public Optional<UserAccount> account(UUID accountId) {
        return accounts.findById(accountId).filter(account -> !account.isSystemAccount());
    }

    /** The fictitious accounts, by username. */
    public List<UserAccount> fictitiousAccounts() {
        return list().stream().filter(SimulationGate::isFictitious).toList();
    }

    /** The account {@code accountId} when it is a fictitious one. */
    public Optional<UserAccount> fictitious(UUID accountId) {
        return accounts.findById(accountId).filter(SimulationGate::isFictitious);
    }

    /** Whether the fictitious account is an admin. */
    public boolean isAdmin(UUID accountId) {
        return registry.admin(accountId);
    }

    /** {@code raw} trimmed, behind the fictitious prefix unless it already is. */
    static String fictitiousTwitchId(String raw) {
        if (isBlank(raw)) {
            throw new IllegalArgumentException("A blank Twitch id");
        }
        String id = raw.trim();
        return SimulationGate.isFictitiousTwitchId(id) ? id : SimulationRegistry.FICTITIOUS_PREFIX + id;
    }

    /** {@code username} trimmed, when no account but {@code self} has it. */
    private String freeUsername(@Nullable String username, @Nullable UUID self) {
        if (isBlank(username)) {
            throw new IllegalArgumentException("A fictitious account needs a username");
        }
        String login = username.trim();
        if (accounts.findByTwitchUsername(login).filter(other -> !other.getId().equals(self)).isPresent()) {
            throw new IllegalArgumentException("The username " + login + " is taken");
        }
        return login;
    }

    /** {@code steamId} trimmed, when no account but {@code self} has it. */
    private String freeSteamId(@Nullable String steamId, @Nullable UUID self) {
        if (isBlank(steamId)) {
            throw new IllegalArgumentException("A blank Steam id");
        }
        String id = steamId.trim();
        if (accounts.findBySteamId(id).filter(other -> !other.getId().equals(self)).isPresent()) {
            throw new IllegalArgumentException("The Steam id " + id + " is taken");
        }
        return id;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String shortSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, TWITCH_ID_SUFFIX_LENGTH);
    }

    /** A fictitious Steam id no account has yet ({@code steam_id} is unique). */
    private String freeSteamId() {
        for (int attempt = 0; attempt < STEAM_ID_ATTEMPTS; attempt++) {
            int suffix = ThreadLocalRandom.current().nextInt((int) Math.pow(10, STEAM_ID_SUFFIX_DIGITS));
            String steamId = STEAM_ID_PREFIX + String.format("%0" + STEAM_ID_SUFFIX_DIGITS + "d", suffix);
            if (accounts.findBySteamId(steamId).isEmpty()) {
                return steamId;
            }
        }
        throw new IllegalStateException("No free fictitious Steam id after " + STEAM_ID_ATTEMPTS + " attempts");
    }

    /**
     * Steam detects its game, as {@code createNewAccount} sets up without a system account; with one, its migrated
     * getters may not have Steam on.
     */
    private void enableSteamGetter(UserAccount account) {
        Optional<GetterConfig> existing = getterConfigs.findByUserAndProvider(account, GetterConfig.Provider.STEAM);
        if (existing.map(GetterConfig::isEnabled).orElse(false)) {
            return;
        }
        GetterConfig steam = existing.orElseGet(() -> {
            GetterConfig created = new GetterConfig();
            created.setUser(account);
            created.setProvider(GetterConfig.Provider.STEAM);
            created.setPriority(getterConfigs.findByUserOrderByPriorityAsc(account).size() + 1);
            return created;
        });
        steam.setEnabled(true);
        getterConfigs.save(steam);
    }
}
