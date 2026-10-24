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

import java.util.Comparator;
import java.util.HashMap;
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

    private record Index(Map<String, UUID> owners, Pattern pattern) {
        static final Index EMPTY = new Index(Map.of(), null);
    }

    private final UserAccountRepository users;
    private final MinecraftFriendLinkRepository links;
    private volatile Index index = Index.EMPTY;

    public OtherMemberMasker(UserAccountRepository users, MinecraftFriendLinkRepository links) {
        this.users = users;
        this.links = links;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    @Transactional(readOnly = true)
    public void reload() {
        try {
            Map<String, UUID> owners = new HashMap<>();
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

    @EventListener
    public void onAccountCreated(AccountCreatedEvent event) {
        reload();
    }

    public String mask(String text, UUID subject) {
        Index current = index;
        if (text == null || text.isEmpty() || current.pattern() == null) {
            return text;
        }
        Matcher m = current.pattern().matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            UUID owner = current.owners().get(m.group().toLowerCase(Locale.ROOT));
            m.appendReplacement(sb, Matcher.quoteReplacement(owner != null && owner.equals(subject) ? m.group() : MARKER));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void add(Map<String, UUID> owners, String identifier, UUID owner) {
        if (identifier == null || owner == null) {
            return;
        }
        String id = identifier.trim();
        if (id.length() >= MIN_LENGTH) {
            owners.put(id.toLowerCase(Locale.ROOT), owner);
        }
    }

    private static Index build(Map<String, UUID> owners) {
        if (owners.isEmpty()) {
            return Index.EMPTY;
        }
        StringBuilder alternation = new StringBuilder();
        owners.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .forEach(id -> alternation.append(alternation.isEmpty() ? "" : "|").append(Pattern.quote(id)));
        Pattern pattern = Pattern.compile(
                "(?<![\\p{L}\\p{N}])(?:" + alternation + ")(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return new Index(Map.copyOf(owners), pattern);
    }
}
