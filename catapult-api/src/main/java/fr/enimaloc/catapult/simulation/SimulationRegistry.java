package fr.enimaloc.catapult.simulation;

import fr.enimaloc.catapult.domain.account.OAuthToken;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.OAuthTokenRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftServiceAccountRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The mode of every external call, per account or globally, the data the simulated platforms answer with, and
 * which fictitious accounts are admins and which were converted from real ones.
 * Kept in a JSON file ({@code catapult.simulation.state-file}), loaded at startup and rewritten on every change.
 * <p>
 * Defaults: a fictitious account (Twitch id starting with {@value #FICTITIOUS_PREFIX}) has every call {@code OK}.
 * A real account too, except the game-resolution global calls ({@link #RESOLUTION_APIS}), which are {@code REAL}
 * when their keys exist. A stored {@code REAL} that is no longer allowed (a token or key gone) reads as {@code OK}:
 * nothing real goes out without what it needs.
 * <p>
 * {@link #realAllowed} rules:
 * <ul>
 *   <li>fictitious or unknown account: never;</li>
 *   <li>{@code twitch.helix}, {@code twitch.chat}, {@code twitch.eventsub}: an {@link OAuthToken} TWITCH exists;</li>
 *   <li>{@code steam}: the account has a {@code steamId};</li>
 *   <li>{@code xbox}: an {@link OAuthToken} XBOX exists;</li>
 *   <li>{@code minecraft.friends}: an enabled Minecraft service account exists;</li>
 *   <li>other {@code minecraft} calls: a Minecraft friend link exists;</li>
 *   <li>{@code battlenet}: never (no per-account link is stored);</li>
 *   <li>{@code twitch.app}: {@code twitch.client-id} and {@code twitch.client-secret} are set;</li>
 *   <li>{@code igdb}: {@code app.igdb.client-id} and {@code twitch.client-secret} are set;</li>
 *   <li>{@code dtdd}: {@code dtdd.enabled} is true and {@code dtdd.api-keys} is set;</li>
 *   <li>{@code gitlab}: {@code gitlab.token} is set;</li>
 *   <li>{@code steam.store}, {@code xbox.store}: public, always.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("catapult.simulation.enabled")
public class SimulationRegistry {

    /**
     * What a fictitious account's Twitch id starts with: a real one is numeric. The {@code mock-} ids made before
     * the prefix was shortened start with it too.
     */
    public static final String FICTITIOUS_PREFIX = "m";

    /** The global APIs that resolve a game: real by default when their keys exist. */
    private static final Set<String> RESOLUTION_APIS = Set.of("igdb", "dtdd", "steam.store", "xbox.store");
    /** Also real by default with their keys: a real game resolution must never carry a made-up app token. */
    private static final Set<String> RESOLUTION_KEYS = Set.of("twitch.app.category-search", "twitch.app.app-token");

    private static final Map<String, CallKey> KEYS_BY_ID = SimulatedCalls.all().stream()
            .collect(Collectors.toUnmodifiableMap(CallKey::id, key -> key));

    /** What the state file holds. */
    record State(Map<String, CallSetting> global, Map<String, Map<String, CallSetting>> accounts,
                 Map<String, SimulatedData> data, Set<String> admins, Set<String> converted) {
        State {
            global = global == null ? Map.of() : global;
            accounts = accounts == null ? Map.of() : accounts;
            data = data == null ? Map.of() : data;
            admins = admins == null ? Set.of() : admins;
            converted = converted == null ? Set.of() : converted;
        }
    }

    private final Path stateFile;
    private final UserAccountRepository accounts;
    private final OAuthTokenRepository tokens;
    private final MinecraftFriendLinkRepository minecraftLinks;
    private final MinecraftServiceAccountRepository minecraftServiceAccounts;
    private final Environment env;
    private final JsonMapper mapper = JsonMapper.builder().build();

    private final Map<String, CallSetting> global = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, CallSetting>> perAccount = new ConcurrentHashMap<>();
    private final Map<UUID, SimulatedData> data = new ConcurrentHashMap<>();
    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();
    private final Set<UUID> converted = ConcurrentHashMap.newKeySet();

    public SimulationRegistry(SimulationProperties properties, UserAccountRepository accounts,
                              OAuthTokenRepository tokens, MinecraftFriendLinkRepository minecraftLinks,
                              MinecraftServiceAccountRepository minecraftServiceAccounts, Environment env) {
        this.stateFile = Path.of(properties.getStateFile());
        this.accounts = accounts;
        this.tokens = tokens;
        this.minecraftLinks = minecraftLinks;
        this.minecraftServiceAccounts = minecraftServiceAccounts;
        this.env = env;
        load();
    }

    /**
     * The setting of {@code key} for the account; {@code accountId} is ignored by a global key. A token refresh is
     * {@code REAL} whatever it is set to while a call that uses the token is: a real call must never run with a
     * stale token, and end in a real bot pause. That is {@code twitch.helix.refresh-token} while any of the account's
     * {@code twitch.helix.*}, {@code twitch.chat.*} or {@code twitch.eventsub.*} calls is {@code REAL}, and {@code twitch.app.bot-refresh} while
     * any account's {@code twitch.chat.*} call or any other {@code twitch.app.*} call is.
     */
    public CallSetting setting(@Nullable UUID accountId, CallKey key) {
        CallSetting own = ownSetting(accountId, key);
        if (own.mode() != CallSetting.Mode.REAL && refreshForcedReal(accountId, key)) {
            return CallSetting.real().withLatency(own.latencyMs());
        }
        return own;
    }

    /**
     * Whether {@link #setting} answers {@code REAL} for {@code key} only because a call that uses the refreshed token
     * is {@code REAL}, whatever {@code key} is set to: the control panel shows it as forced.
     */
    public boolean forcedReal(@Nullable UUID accountId, CallKey key) {
        return ownSetting(accountId, key).mode() != CallSetting.Mode.REAL && refreshForcedReal(accountId, key);
    }

    private boolean refreshForcedReal(@Nullable UUID accountId, CallKey key) {
        if (key.equals(SimulatedCalls.TWITCH_HELIX_REFRESH_TOKEN)) {
            return accountId != null && anyStoredReal(accountId, id ->
                    (id.startsWith("twitch.helix.") || id.startsWith("twitch.chat.")
                            || id.startsWith("twitch.eventsub.")) && !id.equals(key.id()));
        }
        if (key.equals(SimulatedCalls.TWITCH_APP_BOT_REFRESH)) {
            if (!realAllowed(null, key)) {
                return false;
            }
            boolean appCallReal = SimulatedCalls.all().stream()
                    .filter(other -> other.api().equals(key.api()) && !other.equals(key))
                    .anyMatch(other -> ownSetting(null, other).mode() == CallSetting.Mode.REAL);
            return appCallReal || perAccount.keySet().stream()
                    .anyMatch(id -> anyStoredReal(id, callId -> callId.startsWith("twitch.chat.")));
        }
        return false;
    }

    /** Whether one of the account's stored settings whose key {@code matches} is effectively {@code REAL}. */
    private boolean anyStoredReal(UUID accountId, Predicate<String> matches) {
        return perAccount.getOrDefault(accountId, Map.of()).keySet().stream()
                .filter(matches)
                .map(KEYS_BY_ID::get)
                .filter(Objects::nonNull)
                .anyMatch(other -> ownSetting(accountId, other).mode() == CallSetting.Mode.REAL);
    }

    /** The setting of {@code key} as set, or its default, without the forced refreshes. */
    private CallSetting ownSetting(@Nullable UUID accountId, CallKey key) {
        CallSetting stored = key.scope() == CallKey.Scope.GLOBAL
                ? global.get(key.id())
                : accountId == null ? null : perAccount.getOrDefault(accountId, Map.of()).get(key.id());
        if (stored == null) {
            return defaultSetting(accountId, key);
        }
        if (stored.mode() == CallSetting.Mode.REAL && !realAllowed(accountId, key)) {
            return CallSetting.ok().withLatency(stored.latencyMs());
        }
        return stored;
    }

    /**
     * @throws IllegalArgumentException if {@code REAL} is asked where {@link #realAllowed} says no, an
     *                                  {@code ERROR} has no HTTP error status (400..599), or an account key has no
     *                                  account
     */
    public synchronized void set(@Nullable UUID accountId, CallKey key, CallSetting setting) {
        if (setting.mode() == CallSetting.Mode.ERROR && (setting.httpStatus() < 400 || setting.httpStatus() > 599)) {
            throw new IllegalArgumentException("ERROR needs an HTTP error status, not " + setting.httpStatus());
        }
        if (setting.mode() == CallSetting.Mode.REAL && !realAllowed(accountId, key)) {
            throw new IllegalArgumentException("REAL is not allowed for " + key.id());
        }
        if (key.scope() == CallKey.Scope.GLOBAL) {
            global.put(key.id(), setting);
        } else if (accountId == null) {
            throw new IllegalArgumentException(key.id() + " is set per account");
        } else {
            perAccount.computeIfAbsent(accountId, id -> new ConcurrentHashMap<>()).put(key.id(), setting);
        }
        save();
    }

    /** Whether the real client can serve {@code key} for the account, see the class Javadoc for the rules. */
    public boolean realAllowed(@Nullable UUID accountId, CallKey key) {
        if (key.scope() == CallKey.Scope.GLOBAL) {
            return globalKeysPresent(key);
        }
        if (accountId == null) {
            return false;
        }
        UserAccount account = accounts.findById(accountId).orElse(null);
        if (account == null || isFictitious(account)) {
            return false;
        }
        if (key.equals(SimulatedCalls.MINECRAFT_FRIENDS)) {
            return minecraftServiceAccounts.countByEnabledTrue() > 0;
        }
        return switch (key.api()) {
            case "twitch.helix", "twitch.chat", "twitch.eventsub" ->
                    tokens.findByUserAndProvider(account, OAuthToken.Provider.TWITCH).isPresent();
            case "steam" -> account.getSteamId() != null && !account.getSteamId().isBlank();
            case "xbox" -> tokens.findByUserAndProvider(account, OAuthToken.Provider.XBOX).isPresent();
            case "minecraft" -> minecraftLinks.findByUser(account).isPresent();
            default -> false;
        };
    }

    public SimulatedData data(UUID accountId) {
        return data.getOrDefault(accountId, SimulatedData.defaults());
    }

    public synchronized void update(UUID accountId, UnaryOperator<SimulatedData> change) {
        data.put(accountId, change.apply(data(accountId)));
        save();
    }

    /** Whether the fictitious account was made an admin: its fictitious login is given {@code ROLE_ADMIN}. */
    public boolean admin(UUID accountId) {
        return admins.contains(accountId);
    }

    public synchronized void setAdmin(UUID accountId, boolean admin) {
        if (admin) {
            admins.add(accountId);
        } else {
            admins.remove(accountId);
        }
        save();
    }

    /**
     * Whether the account is a real one converted to fictitious: its Twitch id is its real one behind the prefix,
     * so it can be reverted. Not dropped by {@link #forget}, which only drops what the simulation made up.
     */
    public boolean converted(UUID accountId) {
        return converted.contains(accountId);
    }

    public synchronized void setConverted(UUID accountId, boolean isConverted) {
        if (isConverted) {
            converted.add(accountId);
        } else {
            converted.remove(accountId);
        }
        save();
    }

    /** Drops everything kept for a deleted account: its settings, its simulated data and its admin role. */
    public synchronized void forget(UUID accountId) {
        perAccount.remove(accountId);
        data.remove(accountId);
        admins.remove(accountId);
        save();
    }

    private CallSetting defaultSetting(@Nullable UUID accountId, CallKey key) {
        boolean resolution = key.scope() == CallKey.Scope.GLOBAL
                && (RESOLUTION_APIS.contains(key.api()) || RESOLUTION_KEYS.contains(key.id()));
        return resolution && realAllowed(accountId, key) ? CallSetting.real() : CallSetting.ok();
    }

    private boolean isFictitious(UserAccount account) {
        return account.getTwitchId() != null && account.getTwitchId().startsWith(FICTITIOUS_PREFIX);
    }

    private boolean globalKeysPresent(CallKey key) {
        return switch (key.api()) {
            case "twitch.app" -> has("twitch.client-id") && has("twitch.client-secret");
            case "igdb" -> has("app.igdb.client-id") && has("twitch.client-secret");
            case "dtdd" -> env.getProperty("dtdd.enabled", Boolean.class, false) && has("dtdd.api-keys");
            case "gitlab" -> has("gitlab.token");
            case "steam.store", "xbox.store" -> true;
            default -> false;
        };
    }

    private boolean has(String property) {
        String value = env.getProperty(property);
        return value != null && !value.isBlank();
    }

    @PostConstruct
    void logState() {
        log.warn("Simulation layer enabled, state file: {}", stateFile);
    }

    private void load() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            State state = mapper.readValue(stateFile.toFile(), State.class);
            global.putAll(state.global());
            state.accounts().forEach((id, settings) ->
                    perAccount.put(UUID.fromString(id), new ConcurrentHashMap<>(settings)));
            state.data().forEach((id, d) -> data.put(UUID.fromString(id), d));
            state.admins().forEach(id -> admins.add(UUID.fromString(id)));
            state.converted().forEach(id -> converted.add(UUID.fromString(id)));
        } catch (RuntimeException e) {
            global.clear();
            perAccount.clear();
            data.clear();
            admins.clear();
            converted.clear();
            moveAside(e);
        }
    }

    /** Keeps an unreadable state file as {@code <file>.corrupt}, so the next save does not overwrite it. */
    private void moveAside(RuntimeException cause) {
        Path corrupt = stateFile.resolveSibling(stateFile.getFileName() + ".corrupt");
        try {
            Files.move(stateFile, corrupt, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Could not read the simulation state file {}, moved it to {} and starting empty", stateFile,
                    corrupt, cause);
        } catch (IOException e) {
            log.warn("Could not read the simulation state file {} nor move it aside, starting empty", stateFile,
                    cause);
        }
    }

    private void save() {
        Map<String, Map<String, CallSetting>> accountSettings = new HashMap<>();
        perAccount.forEach((id, settings) -> accountSettings.put(id.toString(), Map.copyOf(settings)));
        Map<String, SimulatedData> accountData = new HashMap<>();
        data.forEach((id, d) -> accountData.put(id.toString(), d));
        Set<String> adminIds = admins.stream().map(UUID::toString).collect(Collectors.toSet());
        Set<String> convertedIds = converted.stream().map(UUID::toString).collect(Collectors.toSet());
        State state = new State(Map.copyOf(global), accountSettings, accountData, adminIds, convertedIds);
        try {
            Path parent = stateFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, "simulation", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), state);
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write the simulation state file {}", stateFile, e);
        }
    }
}
