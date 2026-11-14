package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.common.dto.admin.AnkamaAccountDto;
import fr.enimaloc.catapult.common.dto.admin.AnkamaLoginStartDto;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount.AuthState;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.security.TokenEncryptionService;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaAuthException;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient.Account;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient.Certificate;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaHaapiClient.Tokens;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaPkce;
import fr.enimaloc.catapult.service.ankama.haapi.AnkamaShieldException;
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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * An admin's pool of Ankama bot accounts (spec §4.1): the launcher-style PKCE login (the admin opens the authorize
 * URL and pastes back the loopback URL it redirects to), the Shield code of a bot without certificate, re-auth, edits
 * and removal. Whenever an account ends {@code READY}, its chat session is (re)started.
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("ankama.enabled")
public class AnkamaServiceAccountAdmin {

    static final Duration LOGIN_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<AnkamaFriendLink.Status> ACCEPTED = List.of(AnkamaFriendLink.Status.ACCEPTED);

    /** A started login: the PKCE proof key never leaves the server. */
    private record PendingLogin(String proofKey, UUID reauthAccountId, Instant expiresAt) {}

    private final AnkamaServiceAccountRepository accountRepository;
    private final AnkamaFriendLinkRepository linkRepository;
    private final TokenEncryptionService encryption;
    private final AnkamaHaapiClient haapi;
    private final AnkamaSessionManager sessionManager;
    private final GetterConfigRepository getterConfigRepository;
    private final ChannelEventPublisher channelEventPublisher;
    private final Clock clock;

    /** Single use, short lived: a login is consumed by its first completion attempt, whatever the outcome. */
    private final Map<UUID, PendingLogin> pendingLogins = new ConcurrentHashMap<>();
    /** Where the last Shield code of an account was sent (the e-mail domain), until the account is READY. */
    private final Map<UUID, String> shieldCodeDomains = new ConcurrentHashMap<>();

    @Autowired
    public AnkamaServiceAccountAdmin(AnkamaServiceAccountRepository accountRepository,
                                     AnkamaFriendLinkRepository linkRepository, TokenEncryptionService encryption,
                                     AnkamaHaapiClient haapi, AnkamaSessionManager sessionManager,
                                     GetterConfigRepository getterConfigRepository,
                                     ChannelEventPublisher channelEventPublisher) {
        this(accountRepository, linkRepository, encryption, haapi, sessionManager, getterConfigRepository,
                channelEventPublisher, Clock.systemUTC());
    }

    AnkamaServiceAccountAdmin(AnkamaServiceAccountRepository accountRepository,
                              AnkamaFriendLinkRepository linkRepository, TokenEncryptionService encryption,
                              AnkamaHaapiClient haapi, AnkamaSessionManager sessionManager,
                              GetterConfigRepository getterConfigRepository,
                              ChannelEventPublisher channelEventPublisher, Clock clock) {
        this.accountRepository = accountRepository;
        this.linkRepository = linkRepository;
        this.encryption = encryption;
        this.haapi = haapi;
        this.sessionManager = sessionManager;
        this.getterConfigRepository = getterConfigRepository;
        this.channelEventPublisher = channelEventPublisher;
        this.clock = clock;
    }

    public List<AnkamaAccountDto> list() {
        return accountRepository.findAll().stream().map(this::toDto).toList();
    }

    /** Starts a login; {@code reauthAccountId} (nullable) is the bot the login must be the account of. */
    public AnkamaLoginStartDto startLogin(UUID reauthAccountId) {
        if (reauthAccountId != null) findOrThrow(reauthAccountId);
        Instant now = clock.instant();
        pendingLogins.values().removeIf(pending -> pending.expiresAt().isBefore(now));
        AnkamaPkce.Pair pkce = AnkamaPkce.generate();
        UUID loginId = UUID.randomUUID();
        pendingLogins.put(loginId, new PendingLogin(pkce.proofKey(), reauthAccountId, now.plus(LOGIN_TTL)));
        return new AnkamaLoginStartDto(loginId, AnkamaPkce.authorizeUrl(pkce.challenge()));
    }

    /**
     * Finishes a login with the URL the admin pasted. An unknown, expired or already used login, a bad URL, a
     * refused code or a re-auth with another Ankama account give an {@link IllegalArgumentException} (400).
     */
    public AnkamaAccountDto completeLogin(UUID loginId, String pastedUrl) {
        PendingLogin pending = pendingLogins.remove(loginId);
        if (pending == null || pending.expiresAt().isBefore(clock.instant())) {
            throw new IllegalArgumentException("This login is unknown or expired, start it again");
        }
        String code = AnkamaPkce.codeFrom(pastedUrl);
        Tokens tokens;
        Account ankama;
        try {
            tokens = haapi.exchangeCode(code, pending.proofKey());
            ankama = haapi.account(tokens.key());
        } catch (AnkamaAuthException e) {
            log.warn("Ankama refused the login code: {}", e.toString());
            throw new IllegalArgumentException("Ankama refused the login, start it again");
        } catch (RestClientException e) {
            // an HTTP refusal (invalid_grant is a 400) is the admin's to retry; anything else is not their fault
            log.warn("Ankama login failed: {}", e.getClass().getSimpleName());
            if (isServerSide(e)) throw unreachable();
            throw new IllegalArgumentException("Ankama refused the login, start it again");
        }

        AnkamaServiceAccount account;
        if (pending.reauthAccountId() != null) {
            account = findOrThrow(pending.reauthAccountId());
            if (account.getAnkamaAccountId() != ankama.id()) {
                throw new IllegalArgumentException("This is not the same Ankama account as the one to re-authenticate");
            }
        } else {
            account = accountRepository.findByAnkamaAccountId(ankama.id()).orElseGet(() -> newAccount(ankama));
        }
        account.setApiKeyEnc(encryption.encrypt(tokens.key()));
        account.setRefreshTokenEnc(encryption.encrypt(tokens.refreshToken()));
        account.setNickname(ankama.nickname());
        account.setTag(ankama.tag());
        account.setLastRefreshAt(clock.instant());
        account.setUpdatedAt(clock.instant());

        boolean needsCode = ankama.needsShield() && account.getCertificateEnc() == null;
        account.setAuthState(needsCode ? AuthState.CODE_REQUIRED : AuthState.READY);
        AnkamaServiceAccount saved = accountRepository.save(account);
        log.info("Ankama service account {} logged in ({})", saved.getId(), saved.getAuthState());
        if (needsCode) {
            sessionManager.stop(saved.getId());
            sendShieldCode(saved);
        } else {
            shieldCodeDomains.remove(saved.getId());
            sessionManager.restart(saved.getId());
        }
        return toDto(saved);
    }

    /** Validates the Shield code a bot waits for and stores the certificate; a wrong code is a 400 and changes nothing. */
    public void submitShieldCode(UUID id, String code) {
        AnkamaServiceAccount account = findOrThrow(id);
        requireCodeRequired(account);
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code is required");
        }
        Certificate certificate;
        try {
            certificate = haapi.validateShieldCode(encryption.decrypt(account.getApiKeyEnc()), code.strip(),
                    account.getHm1());
        } catch (AnkamaShieldException e) {
            throw new IllegalArgumentException(e.getMessage());
        } catch (AnkamaAuthException e) {
            log.warn("Ankama service account {}: the Shield code could not be checked: {}", id, e.toString());
            throw new IllegalArgumentException("Ankama refused the request, log in again");
        } catch (RestClientException e) {
            // never the exception itself: its message carries the request URI, hence the code and hm1
            log.warn("Ankama service account {}: the Shield code could not be checked ({})", id,
                    e.getClass().getSimpleName());
            throw unreachable();
        }
        AnkamaServiceAccount saved = withRetry(() -> {
            AnkamaServiceAccount fresh = findOrThrow(id);
            fresh.setCertificateId(certificate.id());
            fresh.setCertificateEnc(encryption.encrypt(certificate.encodedCertificate()));
            fresh.setAuthState(AuthState.READY);
            fresh.setUpdatedAt(clock.instant());
            return accountRepository.save(fresh);
        });
        shieldCodeDomains.remove(id);
        log.info("Ankama service account {} registered its Shield certificate", saved.getId());
        sessionManager.restart(id);
    }

    /** Sends a new Shield code to a bot waiting for one. */
    public void resendShieldCode(UUID id) {
        AnkamaServiceAccount account = findOrThrow(id);
        requireCodeRequired(account);
        if (!sendShieldCode(account)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ankama did not send the code");
        }
    }

    /** {@code fillOrder}, {@code enabled} and {@code friendLimitReached}; 404 before 400 for a bad field type. */
    public AnkamaAccountDto update(UUID id, Map<String, Object> body) {
        findOrThrow(id);
        boolean[] enabledChanged = new boolean[1];
        AnkamaServiceAccount saved = withRetry(() -> {
            AnkamaServiceAccount account = findOrThrow(id);
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
     * Stops the session first, then drops the links and the account. Each streamer who was linked through the bot
     * is unlinked as by unenrolling: their Ankama getter is disabled and, once deleted, their dashboards told.
     */
    public void delete(UUID id) {
        findOrThrow(id);
        sessionManager.stop(id);
        List<UUID> owners = new ArrayList<>();
        AnkamaServiceAccount deleted = withRetry(() -> {
            owners.clear();
            AnkamaServiceAccount account = findOrThrow(id);
            List<AnkamaFriendLink> links = linkRepository.findByServiceAccountId(id);
            linkRepository.deleteAll(links);
            for (AnkamaFriendLink link : links) {
                var owner = link.getUser();
                owners.add(owner.getId());
                getterConfigRepository.findByUserAndProvider(owner, GetterConfig.Provider.ANKAMA)
                        .ifPresent(config -> {
                            config.setEnabled(false);
                            getterConfigRepository.save(config);
                        });
            }
            accountRepository.delete(account);
            return account;
        });
        shieldCodeDomains.remove(id);
        log.info("Ankama service account deleted: {} ({} link(s) dropped)", deleted.getId(), owners.size());
        owners.forEach(owner -> channelEventPublisher.publishAnkamaLinkChanged(owner,
                AnkamaLinkStateService.of(AnkamaLinkStateService.NONE)));
    }

    /** A new account: the device id and the Shield secret are stable for the life of the bot. */
    private AnkamaServiceAccount newAccount(Account ankama) {
        AnkamaServiceAccount account = new AnkamaServiceAccount();
        account.setAnkamaAccountId(ankama.id());
        account.setDeviceId(UUID.randomUUID().toString().replace("-", ""));
        byte[] hm1 = new byte[16];
        RANDOM.nextBytes(hm1);
        account.setHm1(HexFormat.of().formatHex(hm1));
        account.setFillOrder((int) accountRepository.count());
        return account;
    }

    /** True once Ankama accepted to send the code; a failure keeps the account waiting so it can be asked again. */
    private boolean sendShieldCode(AnkamaServiceAccount account) {
        try {
            shieldCodeDomains.put(account.getId(), haapi.sendShieldCode(encryption.decrypt(account.getApiKeyEnc())));
            return true;
        } catch (RuntimeException e) {
            log.warn("Ankama service account {}: the Shield code could not be sent ({})", account.getId(),
                    e.getClass().getSimpleName());
            shieldCodeDomains.remove(account.getId());
            return false;
        }
    }

    /** A network failure or an Ankama 5xx: not a refusal of what the admin sent. */
    private static boolean isServerSide(RestClientException e) {
        String message = e.getMessage();
        return e instanceof ResourceAccessException || (message != null && message.matches(".*HTTP 5\\d\\d.*"));
    }

    private static ResponseStatusException unreachable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Ankama could not be reached, try again");
    }

    private static void requireCodeRequired(AnkamaServiceAccount account) {
        if (account.getAuthState() != AuthState.CODE_REQUIRED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is not waiting for a code");
        }
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

    private AnkamaServiceAccount findOrThrow(UUID id) {
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
    private AnkamaAccountDto toDto(AnkamaServiceAccount account) {
        return new AnkamaAccountDto(account.getId(), account.displayTag(), account.getAuthState().name(),
                sessionManager.sessionState(account.getId()), account.getFillOrder(), account.isFriendLimitReached(),
                account.isEnabled(), linkRepository.countByServiceAccountIdAndStatusIn(account.getId(), ACCEPTED),
                shieldCodeDomains.get(account.getId()));
    }
}
