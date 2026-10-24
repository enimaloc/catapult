package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.event.AccountCreatedEvent;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import fr.enimaloc.catapult.service.minecraft.MinecraftService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the identifiers of Catapult members other than the subject with {@code [membre]}.
 * Works from a cached identifier set (no DB access per call), reloaded every 5 minutes and when an account is created.
 */
@Slf4j
@Component
public class OtherMemberMasker {

    static final String MARKER = "[membre]";
    private static final int MIN_LENGTH = 4;

    private record Index(Map<String, Set<UUID>> owners, Pattern pattern) {
        static final Index EMPTY = new Index(Map.of(), null);
    }

    private final UserAccountRepository users;
    private final MinecraftFriendLinkRepository links;
    private volatile Index index = Index.EMPTY;
    private final Executor reloader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "debug-member-masker-reload");
        t.setDaemon(true);
        return t;
    });

    public OtherMemberMasker(UserAccountRepository users, MinecraftFriendLinkRepository links) {
        this.users = users;
        this.links = links;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    @Transactional(readOnly = true)
    public void reload() {
        try {
            Map<String, Set<UUID>> owners = new HashMap<>();
            for (UserAccount u : users.findAll()) {
                add(owners, u.getTwitchId(), u.getId());
                add(owners, u.getTwitchUsername(), u.getId());
                add(owners, u.getSteamId(), u.getId());
            }
            for (MinecraftFriendLink l : links.findAll()) {
                UUID owner = l.getUser().getId();
                String profile = l.getMinecraftProfileId();
                add(owners, MinecraftService.normalizeProfileId(profile), owner);
                add(owners, profile, owner);
                add(owners, l.getMinecraftName(), owner);
            }
            index = build(owners);
        } catch (RuntimeException e) {
            log.warn("Could not reload the member identifiers used to mask debug exchanges", e);
        }
    }

    /** Reloads after the creating transaction commits, on another thread, so a failure can never affect the login. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountCreated(AccountCreatedEvent event) {
        reloader.execute(this::reload);
    }

    public String mask(String text, UUID subject) {
        Index current = index;
        if (text == null || text.isEmpty() || current.pattern() == null) {
            return text;
        }
        Matcher m = current.pattern().matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Set<UUID> owners = current.owners().get(m.group().toLowerCase(Locale.ROOT));
            boolean keep = owners != null && owners.size() == 1 && owners.contains(subject);
            m.appendReplacement(sb, Matcher.quoteReplacement(keep ? m.group() : MARKER));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void add(Map<String, Set<UUID>> owners, String identifier, UUID owner) {
        if (identifier == null || owner == null) {
            return;
        }
        String id = identifier.trim();
        if (id.length() >= MIN_LENGTH) {
            owners.computeIfAbsent(id.toLowerCase(Locale.ROOT), k -> new HashSet<>()).add(owner);
        }
    }

    private static Index build(Map<String, Set<UUID>> owners) {
        if (owners.isEmpty()) {
            return Index.EMPTY;
        }
        StringBuilder alternation = new StringBuilder();
        owners.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .forEach(id -> alternation.append(alternation.isEmpty() ? "" : "|").append(Pattern.quote(id)));
        Pattern pattern = Pattern.compile(
                "(?<![\\p{L}\\p{N}_])(?:" + alternation + ")(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Map<String, Set<UUID>> frozen = new HashMap<>();
        owners.forEach((k, v) -> frozen.put(k, Set.copyOf(v)));
        return new Index(Map.copyOf(frozen), pattern);
    }
}
