package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.common.dto.admin.EpicAccountDto;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.epic.api.EpicAccountClient;
import fr.enimaloc.catapult.service.epic.api.EpicAccountClient.Tokens;
import fr.enimaloc.catapult.service.epic.api.EpicAuthException;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An admin's pool of Epic bot accounts (spec §4.1): a bot is added, or logged in again, with the authorization code
 * Epic shows at {@link #CODE_URL} to whoever is logged in there; then edits and removal. Whenever a login is stored,
 * the bot's session is (re)started.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("epic.enabled")
public class EpicServiceAccountAdmin {

    /** Epic's page that shows the launcher authorization code of the account logged in on epicgames.com. */
    public static final String CODE_URL =
            "https://www.epicgames.com/id/api/redirect?clientId=34a02cf8f4414e29b15921876da36f9a&responseType=code";
    /** A bot whose refresh token expires within this is flagged to the admin. */
    static final Duration EXPIRES_SOON = Duration.ofDays(30);

    static final String LOG_IN_FIRST = "log in with the bot account first, then reload the page";
    static final String NO_CODE = "no authorization code found";
    static final String CODE_EXPIRED = "this code expired or was already used, get a new one";
    static final String NOT_SAME_ACCOUNT = "this is not the same Epic account";
    static final String STREAMER_ACCOUNT = "this Epic account is linked to a streamer, log in with a bot account";

    private static final Pattern NULL_CODE = Pattern.compile("\"authorizationCode\"\\s*:\\s*null");
    private static final Pattern JSON_CODE =
            Pattern.compile("\"authorizationCode\"\\s*:\\s*\"\\s*([0-9a-fA-F]{32})\\s*\"");
    private static final Pattern BARE_CODE = Pattern.compile("[0-9a-fA-F]{32}");
    private static final List<EpicFriendLink.Status> ACCEPTED = List.of(EpicFriendLink.Status.ACCEPTED);

    private final EpicServiceAccountRepository accountRepository;
    private final EpicFriendLinkRepository linkRepository;
    private final TokenEncryptionService encryption;
    private final EpicAccountClient accountClient;
    private final EpicTokenService tokenService;
    private final EpicSessionManager sessionManager;
    private final GetterConfigRepository getterConfigRepository;
    private final ChannelEventPublisher channelEventPublisher;
    private final Clock clock;

    @Autowired
    public EpicServiceAccountAdmin(EpicServiceAccountRepository accountRepository,
                                   EpicFriendLinkRepository linkRepository, TokenEncryptionService encryption,
                                   EpicAccountClient accountClient, EpicTokenService tokenService,
                                   EpicSessionManager sessionManager, GetterConfigRepository getterConfigRepository,
                                   ChannelEventPublisher channelEventPublisher) {
        this(accountRepository, linkRepository, encryption, accountClient, tokenService, sessionManager,
                getterConfigRepository, channelEventPublisher, Clock.systemUTC());
    }

    EpicServiceAccountAdmin(EpicServiceAccountRepository accountRepository, EpicFriendLinkRepository linkRepository,
                            TokenEncryptionService encryption, EpicAccountClient accountClient,
                            EpicTokenService tokenService, EpicSessionManager sessionManager,
                            GetterConfigRepository getterConfigRepository,
                            ChannelEventPublisher channelEventPublisher, Clock clock) {
        this.accountRepository = accountRepository;
        this.linkRepository = linkRepository;
        this.encryption = encryption;
        this.accountClient = accountClient;
        this.tokenService = tokenService;
        this.sessionManager = sessionManager;
        this.getterConfigRepository = getterConfigRepository;
        this.channelEventPublisher = channelEventPublisher;
        this.clock = clock;
    }

    /**
     * The authorization code in what the admin pasted: the whole JSON {@link #CODE_URL} shows (its
     * {@code authorizationCode}) or the code alone, quotes and spaces allowed.
     *
     * @throws IllegalArgumentException when the page was opened logged out, or nothing pasted holds a code
     */
    public static String codeFrom(String pasted) {
        String text = pasted == null ? "" : pasted.strip();
        Matcher json = JSON_CODE.matcher(text);
        if (json.find()) {
            return json.group(1).toLowerCase();
        }
        if (NULL_CODE.matcher(text).find()) {
            throw new IllegalArgumentException(LOG_IN_FIRST);
        }
        String bare = text.replace("\"", "").replace("'", "").strip();
        if (BARE_CODE.matcher(bare).matches()) {
            return bare.toLowerCase();
        }
        throw new IllegalArgumentException(NO_CODE);
    }

    public List<EpicAccountDto> list() {
        return accountRepository.findAll().stream().map(this::toDto).toList();
    }

    /**
     * Adds the bot whose authorization code was pasted, or logs it in again when it is already in the pool. With
     * {@code reauthAccountId} (nullable), the code must be that bot's. A missing, expired or used code, another Epic
     * account than the one to re-authenticate, or a streamer's linked account give an
     * {@link IllegalArgumentException} (400); an unknown {@code reauthAccountId} a 404.
     */
    public EpicAccountDto addOrReauth(String pasted, UUID reauthAccountId) {
        String code = codeFrom(pasted);
        EpicServiceAccount reauth = reauthAccountId == null ? null : findOrThrow(reauthAccountId);
        Tokens tokens;
        try {
            tokens = accountClient.exchangeCode(code);
        } catch (EpicAuthException e) {
            // whatever the reason, a code the token endpoint refuses cannot be used
            log.warn("Epic refused an authorization code: {}", e.reason());
            throw new IllegalArgumentException(CODE_EXPIRED);
        } catch (RestClientException e) {
            // never the exception itself: only its type, the request carried the code
            log.warn("Epic authorization code exchange failed ({})", e.getClass().getSimpleName());
            if (isServerSide(e)) throw unreachable();
            throw new IllegalArgumentException(CODE_EXPIRED);
        }
        String epicAccountId = tokens.accountId();
        if (!linkRepository.findByEpicAccountIdAndStatusNot(epicAccountId, EpicFriendLink.Status.REMOVED).isEmpty()) {
            log.warn("Refused to add Epic account {} as a bot: it is a streamer's linked account", epicAccountId);
            throw new IllegalArgumentException(STREAMER_ACCOUNT);
        }

        EpicServiceAccount existing;
        if (reauth != null) {
            if (!reauth.getEpicAccountId().equals(epicAccountId)) {
                throw new IllegalArgumentException(NOT_SAME_ACCOUNT);
            }
            existing = reauth;
        } else {
            existing = accountRepository.findByEpicAccountId(epicAccountId).orElse(null);
        }

        UUID id;
        if (existing == null) {
            id = accountRepository.save(newAccount(tokens)).getId();
            log.info("Epic service account {} added ({})", id, epicAccountId);
        } else {
            id = existing.getId();
        }
        tokenService.install(id, tokens);
        if (existing != null && hasText(tokens.displayName())
                && !tokens.displayName().equals(existing.getDisplayName())) {
            withRetry(() -> {
                EpicServiceAccount fresh = findOrThrow(id);
                fresh.setDisplayName(tokens.displayName());
                return accountRepository.save(fresh);
            });
        }
        log.info("Epic service account {} logged in", id);
        sessionManager.restart(id);
        return toDto(findOrThrow(id));
    }

    /** {@code fillOrder}, {@code enabled} and {@code friendLimitReached}; 404 before 400 for a bad field type. */
    public EpicAccountDto update(UUID id, Map<String, Object> body) {
        findOrThrow(id);
        boolean[] enabledChanged = new boolean[1];
        EpicServiceAccount saved = withRetry(() -> {
            EpicServiceAccount account = findOrThrow(id);
            enabledChanged[0] = false;
            if (body.containsKey("enabled")) {
                boolean enabled = field(body, "enabled", Boolean.class);
                enabledChanged[0] = enabled != account.isEnabled();
                account.setEnabled(enabled);
            }
            if (body.containsKey("friendLimitReached")) {
                account.setFriendLimitReached(field(body, "friendLimitReached", Boolean.class));
            }
            if (body.containsKey("fillOrder")) {
                account.setFillOrder(field(body, "fillOrder", Number.class).intValue());
            }
            account.setUpdatedAt(clock.instant());
            return accountRepository.save(account);
        });
        if (enabledChanged[0]) {
            sessionManager.restart(id);
        }
        return toDto(saved);
    }

    /**
     * Stops the session and forgets the bot's tokens first, then drops the links and the account. Each streamer who
     * was linked through the bot is unlinked as by unenrolling: their Epic getter is disabled and, once deleted, their
     * dashboards told.
     */
    public void delete(UUID id) {
        findOrThrow(id);
        sessionManager.stop(id);
        tokenService.forget(id);
        List<UUID> owners = new ArrayList<>();
        EpicServiceAccount deleted = withRetry(() -> {
            owners.clear();
            EpicServiceAccount account = findOrThrow(id);
            List<EpicFriendLink> links = linkRepository.findByServiceAccountId(id);
            linkRepository.deleteAll(links);
            for (EpicFriendLink link : links) {
                var owner = link.getUser();
                owners.add(owner.getId());
                getterConfigRepository.findByUserAndProvider(owner, GetterConfig.Provider.EPIC)
                        .ifPresent(config -> {
                            config.setEnabled(false);
                            getterConfigRepository.save(config);
                        });
            }
            accountRepository.delete(account);
            return account;
        });
        log.info("Epic service account deleted: {} ({} link(s) dropped)", deleted.getId(), owners.size());
        owners.forEach(owner -> channelEventPublisher.publishEpicLinkChanged(owner,
                EpicLinkStateService.of(EpicLinkStateService.NONE)));
    }

    /** A new bot, {@code READY} with the login's refresh token; last in the fill order. */
    private EpicServiceAccount newAccount(Tokens tokens) {
        Instant now = clock.instant();
        EpicServiceAccount account = new EpicServiceAccount();
        account.setEpicAccountId(tokens.accountId());
        account.setDisplayName(hasText(tokens.displayName()) ? tokens.displayName() : tokens.accountId());
        account.setRefreshTokenEnc(encryption.encrypt(tokens.refreshToken()));
        account.setRefreshExpiresAt(tokens.refreshExpiresAt());
        account.setLastRefreshAt(now);
        account.setAuthState(AuthState.READY);
        account.setFillOrder((int) accountRepository.count());
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return account;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** A network failure or an Epic 5xx: not a refusal of what the admin sent. */
    private static boolean isServerSide(RestClientException e) {
        String message = e.getMessage();
        return e instanceof ResourceAccessException || (message != null && message.matches(".*HTTP 5\\d\\d.*"));
    }

    private static ResponseStatusException unreachable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Epic could not be reached, try again");
    }

    /** Runs a read-modify-write again once when a token refresh committed in between, 409 when it still conflicts. */
    private <T> T withRetry(Supplier<T> write) {
        try {
            return write.get();
        } catch (ObjectOptimisticLockingFailureException first) {
            try {
                return write.get();
            } catch (ObjectOptimisticLockingFailureException second) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The account was modified concurrently");
            }
        }
    }

    private EpicServiceAccount findOrThrow(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown account"));
    }

    private static <T> T field(Map<String, Object> body, String name, Class<T> type) {
        Object value = body.get(name);
        if (!type.isInstance(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid type for " + name);
        }
        return type.cast(value);
    }

    /** The friend count is the ACCEPTED links in the database, so it needs no live session. */
    private EpicAccountDto toDto(EpicServiceAccount account) {
        Instant expiresAt = account.getRefreshExpiresAt();
        boolean expiresSoon = expiresAt != null && expiresAt.isBefore(clock.instant().plus(EXPIRES_SOON));
        return new EpicAccountDto(account.getId(), account.getDisplayName(), account.getAuthState().name(),
                sessionManager.sessionState(account.getId()), account.getFillOrder(), account.isFriendLimitReached(),
                account.isEnabled(), linkRepository.countByServiceAccountIdAndStatusIn(account.getId(), ACCEPTED),
                expiresAt, expiresSoon);
    }
}
