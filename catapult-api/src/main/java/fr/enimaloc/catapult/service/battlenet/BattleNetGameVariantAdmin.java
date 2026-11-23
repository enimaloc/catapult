package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantDto;
import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetGameVariant;
import fr.enimaloc.catapult.domain.battlenet.BattleNetGameVariant.MatchType;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetGameVariantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The admin side of the Battle.net game variants: list, create, change and delete them. Each write, once committed,
 * makes the detection read the variants again ({@link BattleNetGameVariants#invalidate}); the other instance follows
 * within its refresh. Errors are 400 (invalid), 404 (unknown id) and 409 (the game already has that key).
 */
@Service
public class BattleNetGameVariantAdmin {

    private static final Pattern FOURCC = Pattern.compile("[A-Za-z0-9]{1,4}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9-]{1,32}");
    static final int MAX_PATTERNS = 10;
    static final int MAX_TEXT = 100;

    private final BattleNetGameVariantRepository repository;
    private final UserAccountRepository users;
    private final BattleNetGameVariants variants;

    public BattleNetGameVariantAdmin(BattleNetGameVariantRepository repository, UserAccountRepository users,
                                     BattleNetGameVariants variants) {
        this.repository = repository;
        this.users = users;
        this.variants = variants;
    }

    /** Every variant, by game then key. */
    @Transactional(readOnly = true)
    public List<BattleNetGameVariantDto> list() {
        List<BattleNetGameVariant> all = new ArrayList<>(repository.findAll());
        all.sort(Comparator.comparing(BattleNetGameVariant::getFourcc)
                .thenComparing(BattleNetGameVariant::getVariantKey));
        Map<UUID, String> logins = new HashMap<>();
        List<UUID> admins = all.stream().map(BattleNetGameVariant::getUpdatedBy).filter(Objects::nonNull).distinct()
                .toList();
        users.findAllById(admins).forEach(u -> logins.put(u.getId(), u.getTwitchUsername()));
        return all.stream().map(v -> toDto(v, logins.get(v.getUpdatedBy()))).toList();
    }

    @Transactional
    public BattleNetGameVariantDto create(BattleNetGameVariantRequest request, UserAccount admin) {
        if (request == null) throw badRequest("Missing body");
        if (request.fourcc() == null || !FOURCC.matcher(request.fourcc()).matches()) {
            throw badRequest("The game must be a Battle.net code of 1 to 4 letters or digits");
        }
        if (request.variantKey() == null || !KEY.matcher(request.variantKey()).matches()) {
            throw badRequest("The key must be 1 to 32 letters, digits or dashes");
        }
        if (repository.existsByFourccAndVariantKey(request.fourcc(), request.variantKey())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This game already has a variant with that key");
        }
        BattleNetGameVariant variant = new BattleNetGameVariant();
        variant.setFourcc(request.fourcc());
        variant.setVariantKey(request.variantKey());
        return save(variant, request, admin);
    }

    @Transactional
    public BattleNetGameVariantDto update(UUID id, BattleNetGameVariantRequest request, UserAccount admin) {
        BattleNetGameVariant variant = find(id);
        if (request == null) throw badRequest("Missing body");
        if (!variant.getFourcc().equals(request.fourcc()) || !variant.getVariantKey().equals(request.variantKey())) {
            throw badRequest("The game and the key of a variant cannot change");
        }
        return save(variant, request, admin);
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(find(id));
        invalidateOnceCommitted();
    }

    private BattleNetGameVariantDto save(BattleNetGameVariant variant, BattleNetGameVariantRequest request,
                                         UserAccount admin) {
        variant.setMatchType(matchType(request.matchType()));
        variant.setPatterns(patterns(request.patterns()));
        variant.setDisplayName(displayName(request.displayName()));
        variant.setEnabled(request.enabled());
        variant.setUpdatedBy(admin == null ? null : admin.getId());
        BattleNetGameVariant saved = repository.save(variant);
        invalidateOnceCommitted();
        return toDto(saved, admin == null ? null : admin.getTwitchUsername());
    }

    /**
     * Makes the detection read the variants again once the change is committed: before, it would read and cache the
     * old rows until the next refresh.
     */
    private void invalidateOnceCommitted() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            variants.invalidate();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                variants.invalidate();
            }
        });
    }

    private BattleNetGameVariant find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown game variant"));
    }

    private static MatchType matchType(String raw) {
        try {
            return MatchType.valueOf(Objects.requireNonNull(raw));
        } catch (RuntimeException e) {
            throw badRequest("The match must be PREFIX or CONTAINS");
        }
    }

    /** As typed, but for the {@code \r} a pasted line may end with: a trailing space is part of the pattern. */
    private static List<String> patterns(List<String> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > MAX_PATTERNS) {
            throw badRequest("Give 1 to " + MAX_PATTERNS + " patterns");
        }
        List<String> patterns = new ArrayList<>();
        for (String pattern : raw) {
            String kept = pattern == null ? "" : pattern.endsWith("\r") ? pattern.substring(0, pattern.length() - 1)
                    : pattern;
            if (kept.isBlank() || kept.length() > MAX_TEXT) {
                throw badRequest("A pattern must be 1 to " + MAX_TEXT + " characters, not only spaces");
            }
            patterns.add(kept);
        }
        return patterns;
    }

    private static String displayName(String raw) {
        if (raw == null || raw.isBlank() || raw.strip().length() > MAX_TEXT) {
            throw badRequest("The name must be 1 to " + MAX_TEXT + " characters");
        }
        return raw.strip();
    }

    private static BattleNetGameVariantDto toDto(BattleNetGameVariant v, String updatedBy) {
        return new BattleNetGameVariantDto(v.getId(), v.getFourcc(), v.getVariantKey(), v.sourceId(),
                v.getMatchType().name(), List.copyOf(v.getPatterns()), v.getDisplayName(), v.isEnabled(),
                v.getUpdatedAt(), updatedBy);
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
