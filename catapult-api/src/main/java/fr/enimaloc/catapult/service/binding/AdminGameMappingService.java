package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.common.dto.admin.DetectedGameDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingDto;
import fr.enimaloc.catapult.common.dto.admin.GameMappingRequest;
import fr.enimaloc.catapult.common.dto.admin.GameMappingResult;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GameBinding.SourceType;
import fr.enimaloc.catapult.domain.binding.GameBinding.Status;
import fr.enimaloc.catapult.domain.binding.GameMapping;
import fr.enimaloc.catapult.event.listener.GameEventListener;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameMappingRepository;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import fr.enimaloc.catapult.service.twitch.ChannelUpdateOutcome;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The admin game mappings: an admin sets the Twitch category of a detected game (platform + its ID) for every
 * streamer. Saving one applies it to the game's AUTO, INCOMPLETE and MAPPED bindings (never to a streamer's MANUAL
 * choice), deleting one sends its MAPPED bindings back to IGDB, in the same transaction as the mapping itself.
 * After the commit, each changed binding is pushed to its streamer's dashboard and, when asked, the channels live
 * on that game get it on Twitch through the detection's own path ({@link GameEventListener#applyBinding}); a Twitch
 * failure there is logged and counted, the mapping stays.
 */
@Slf4j
@Service
public class AdminGameMappingService {

    /** How many detected games the form's suggestions show. */
    static final int DETECTED_LIMIT = 20;
    static final int MAX_PAGE_SIZE = 100;

    /** The bindings a mapping applies to: every status but the streamer's MANUAL choice. */
    private static final List<Status> MAPPABLE = List.of(Status.AUTO, Status.INCOMPLETE, Status.MAPPED);

    private final GameMappingRepository mappingRepository;
    private final GameBindingRepository bindingRepository;
    private final UserAccountRepository userAccountRepository;
    private final BindingService bindingService;
    private final GameStateService gameStateService;
    private final StreamStateService streamStateService;
    private final ChannelEventPublisher channelEventPublisher;
    private final GameEventListener gameEventListener;
    private final TransactionTemplate transactions;

    public AdminGameMappingService(GameMappingRepository mappingRepository, GameBindingRepository bindingRepository,
                                   UserAccountRepository userAccountRepository, BindingService bindingService,
                                   GameStateService gameStateService, StreamStateService streamStateService,
                                   ChannelEventPublisher channelEventPublisher, GameEventListener gameEventListener,
                                   PlatformTransactionManager transactionManager) {
        this.mappingRepository = mappingRepository;
        this.bindingRepository = bindingRepository;
        this.userAccountRepository = userAccountRepository;
        this.bindingService = bindingService;
        this.gameStateService = gameStateService;
        this.streamStateService = streamStateService;
        this.channelEventPublisher = channelEventPublisher;
        this.gameEventListener = gameEventListener;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** A game: its platform (enum name) and ID. */
    private record GameKey(String sourceType, String sourceId) {
        static GameKey of(Object sourceType, Object sourceId) {
            return new GameKey(String.valueOf(sourceType), String.valueOf(sourceId));
        }
    }

    /** What a write did in its transaction: the mapping (null once deleted), the bindings changed, the live ones. */
    private record Written(GameMappingDto mapping, List<GameBinding> changed, List<GameBinding> live) {}

    /** A validated request: the platform, the trimmed ID and category. */
    private record Target(SourceType sourceType, String sourceId, String twitchGameId, String twitchGameName) {}

    // ---- Listings

    /** The mappings, last changed first. */
    public Page<GameMappingDto> list(int page, int size) {
        Page<GameMapping> found = mappingRepository.findAll(
                PageRequest.of(Math.max(page, 0), pageSize(size), Sort.by(Sort.Direction.DESC, "updatedAt")));
        return new PageImpl<>(toDtos(found.getContent(), null), found.getPageable(), found.getTotalElements());
    }

    /** The detected games without a mapping that have an INCOMPLETE binding, most streamers first. */
    public Page<DetectedGameDto> missing(int page, int size) {
        Page<Object[]> rows = bindingRepository.findGamesMissingAMapping(
                PageRequest.of(Math.max(page, 0), pageSize(size)));
        Map<GameKey, String> names = mostFrequentNames(rows.getContent().stream().map(r -> GameKey.of(r[0], r[1])).toList());
        return rows.map(r -> detectedGame(r, names));
    }

    /**
     * The games detected on a platform (not MANUAL) whose ID starts with {@code q} or one of whose names contains it,
     * ignoring case: at most {@link #DETECTED_LIMIT}, most streamers first. A blank {@code q} gives the most common.
     */
    public List<DetectedGameDto> detected(String sourceType, String q) {
        SourceType platform = platform(sourceType);
        String needle = escapeLike(q == null ? "" : q.trim().toLowerCase(Locale.ROOT));
        List<Object[]> rows = bindingRepository.findDetectedGames(platform, needle + "%", "%" + needle + "%",
                PageRequest.of(0, DETECTED_LIMIT));
        Map<GameKey, String> names = mostFrequentNames(rows.stream().map(r -> GameKey.of(r[0], r[1])).toList());
        return rows.stream().map(r -> detectedGame(r, names)).toList();
    }

    // ---- Writes

    public GameMappingResult create(GameMappingRequest request, UserAccount admin) {
        Target target = validate(request);
        BindingService.MappingSuggestions suggestions = igdbSuggestions(target, request);
        return write(request.applyLive(), () -> {
            if (mappingRepository.findBySourceTypeAndSourceId(target.sourceType(), target.sourceId()).isPresent()) {
                throw duplicate();
            }
            GameMapping mapping = new GameMapping();
            fill(mapping, target, request, admin);
            GameMapping saved = save(mapping);
            List<GameBinding> changed = applyToBindings(saved, suggestions);
            log.info("Game mapping {} {} created by {} — '{}' (twitchId={}), {} binding(s) updated",
                    saved.getSourceType(), saved.getSourceId(), admin == null ? null : admin.getId(),
                    saved.getTwitchGameName(), saved.getTwitchGameId(), changed.size());
            return new Written(toDto(saved, admin), changed, List.of());
        });
    }

    public GameMappingResult update(UUID id, GameMappingRequest request, UserAccount admin) {
        Target target = validate(request);
        BindingService.MappingSuggestions suggestions = igdbSuggestions(target, request);
        return write(request.applyLive(), () -> {
            GameMapping mapping = existing(id);
            boolean moved = mapping.getSourceType() != target.sourceType()
                    || !mapping.getSourceId().equals(target.sourceId());
            List<GameBinding> changed = new ArrayList<>();
            if (moved) {
                if (mappingRepository.findBySourceTypeAndSourceId(target.sourceType(), target.sourceId()).isPresent()) {
                    throw duplicate();
                }
                // Now about another game: the old one's bindings go back to IGDB.
                changed.addAll(backToIgdb(mapping.getSourceType(), mapping.getSourceId()));
            }
            fill(mapping, target, request, admin);
            GameMapping saved = save(mapping);
            changed.addAll(applyToBindings(saved, suggestions));
            log.info("Game mapping {} {} updated by {} — '{}' (twitchId={}), {} binding(s) updated",
                    saved.getSourceType(), saved.getSourceId(), admin == null ? null : admin.getId(),
                    saved.getTwitchGameName(), saved.getTwitchGameId(), changed.size());
            return new Written(toDto(saved, admin), changed, List.of());
        });
    }

    public GameMappingResult delete(UUID id, boolean applyLive) {
        return write(applyLive, () -> {
            GameMapping mapping = existing(id);
            mappingRepository.delete(mapping);
            List<GameBinding> changed = backToIgdb(mapping.getSourceType(), mapping.getSourceId());
            log.info("Game mapping {} {} deleted — {} binding(s) back to IGDB",
                    mapping.getSourceType(), mapping.getSourceId(), changed.size());
            return new Written(null, changed, List.of());
        });
    }

    /**
     * Runs the write in a transaction, which also picks the changed bindings whose streamer is live on their game
     * (when {@code applyLive}); once it is committed, pushes the bindings to the dashboards and the live ones to Twitch.
     */
    private GameMappingResult write(boolean applyLive, Supplier<Written> work) {
        Written written = transactions.execute(status -> {
            Written w = work.get();
            return new Written(w.mapping(), w.changed(), applyLive ? liveOnTheirGame(w.changed()) : List.of());
        });
        Objects.requireNonNull(written);

        for (GameBinding binding : written.changed()) {
            try {
                channelEventPublisher.bindingUpserted(binding.getUser().getId(), binding);
            } catch (RuntimeException e) {
                log.warn("Binding {} not pushed to its dashboard: {}", binding.getId(), e.getMessage());
            }
        }

        int updated = 0;
        int failed = 0;
        for (GameBinding binding : written.live()) {
            UserAccount user = binding.getUser();
            ChannelUpdateOutcome outcome;
            try {
                outcome = gameEventListener.applyBindingIfLive(user, binding);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Live channel of user {} not updated after a game mapping change: {}",
                        user.getId(), e.getMessage(), e);
                continue;
            }
            // Skipped (offline since the transaction picked it, bot disabled, no fallback for an INCOMPLETE binding):
            // neither updated nor failed, never kept pending.
            switch (outcome) {
                case APPLIED -> updated++;
                case FAILED -> {
                    failed++;
                    log.warn("Twitch did not update the live channel of user {} after a game mapping change",
                            user.getId());
                }
                case SKIPPED -> { }
            }
        }
        return new GameMappingResult(written.mapping(), written.changed().size(), updated, failed);
    }

    /** The changed bindings whose streamer is live and plays their game now (not ignored ones: no-game fallback). */
    private List<GameBinding> liveOnTheirGame(List<GameBinding> changed) {
        if (changed.isEmpty()) return List.of();
        Map<UUID, DetectedGame> playing = gameStateService.lastKnownGames();
        List<GameBinding> live = new ArrayList<>();
        for (GameBinding binding : changed) {
            if (binding.isIgnored()) continue;
            DetectedGame game = playing.get(binding.getUser().getId());
            if (game == null || game.getSourceType() != binding.getSourceType()
                    || !Objects.equals(game.getSourceId(), binding.getSourceId())) continue;
            if (!streamStateService.isLive(binding.getUser())) continue;
            // Loaded now: the Twitch update reads the account after the transaction.
            Hibernate.initialize(binding.getUser());
            live.add(binding);
        }
        return live;
    }

    /**
     * IGDB's suggestions for the request's game, resolved before the transaction (by its ID, else its most detected
     * name) for the CCLs / TWs the mapping keeps from IGDB, so no IGDB call happens while its bindings are locked.
     */
    private BindingService.MappingSuggestions igdbSuggestions(Target target, GameMappingRequest request) {
        if (request.ccls() != null && request.tws() != null) return BindingService.MappingSuggestions.NONE;
        GameKey game = GameKey.of(target.sourceType(), target.sourceId());
        String name = mostFrequentNames(List.of(game)).get(game);
        return bindingService.igdbSuggestions(new DetectedGame(target.sourceId(), target.sourceType(), name),
                request.ccls() == null, request.tws() == null);
    }

    /**
     * Applies the mapping to its game's bindings but MANUAL ones, saving those it changes. The rows are locked
     * first and their status checked again: a streamer who just chose MANUAL keeps it.
     */
    private List<GameBinding> applyToBindings(GameMapping mapping, BindingService.MappingSuggestions suggestions) {
        List<GameBinding> changed = new ArrayList<>();
        for (GameBinding binding : bindingRepository.lockAllBySourceTypeAndSourceIdAndStatusIn(
                mapping.getSourceType(), mapping.getSourceId(), MAPPABLE)) {
            if (binding.getStatus() == Status.MANUAL) continue;
            if (bindingService.applyMapping(binding, mapping, suggestions)) {
                bindingRepository.save(binding);
                changed.add(binding);
            }
        }
        return changed;
    }

    /**
     * Re-resolves the game's MAPPED bindings from IGDB alone (AUTO or INCOMPLETE again); they are saved. The rows
     * are locked first and their status checked again, as in {@link #applyToBindings}.
     */
    private List<GameBinding> backToIgdb(SourceType sourceType, String sourceId) {
        List<GameBinding> changed = new ArrayList<>();
        for (GameBinding binding : bindingRepository.lockAllBySourceTypeAndSourceIdAndStatusIn(
                sourceType, sourceId, List.of(Status.MAPPED))) {
            if (binding.getStatus() != Status.MAPPED) continue;
            changed.add(bindingService.resolveWithoutMapping(binding));
        }
        return changed;
    }

    private GameMapping save(GameMapping mapping) {
        try {
            return mappingRepository.saveAndFlush(mapping);
        } catch (DataIntegrityViolationException e) {
            // Another admin saved the same game meanwhile: the unique (source_type, source_id) caught it.
            throw duplicate();
        }
    }

    private static void fill(GameMapping mapping, Target target, GameMappingRequest request, UserAccount admin) {
        mapping.setSourceType(target.sourceType());
        mapping.setSourceId(target.sourceId());
        mapping.setTwitchGameId(target.twitchGameId());
        mapping.setTwitchGameName(target.twitchGameName());
        mapping.setCclsOverridden(request.ccls() != null);
        mapping.getCcls().clear();
        if (request.ccls() != null) mapping.getCcls().addAll(request.ccls());
        mapping.setTwsOverridden(request.tws() != null);
        mapping.getTws().clear();
        if (request.tws() != null) mapping.getTws().addAll(request.tws());
        mapping.setUpdatedBy(admin == null ? null : admin.getId());
    }

    private GameMapping existing(UUID id) {
        return mappingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown game mapping"));
    }

    private static ResponseStatusException duplicate() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "This game already has a mapping");
    }

    private static Target validate(GameMappingRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing body");
        SourceType platform = platform(request.sourceType());
        String sourceId = request.sourceId() == null ? "" : request.sourceId().trim();
        if (sourceId.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing game ID");
        String twitchGameId = request.twitchGameId() == null ? "" : request.twitchGameId().trim();
        String twitchGameName = request.twitchGameName() == null ? "" : request.twitchGameName().trim();
        if (twitchGameId.isEmpty() || twitchGameName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Twitch category");
        }
        return new Target(platform, sourceId, twitchGameId, twitchGameName);
    }

    /** A detected game's platform; MANUAL is not one (its ID is an IGDB ID set by the chat command). */
    private static SourceType platform(String raw) {
        if (raw == null || raw.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing platform");
        SourceType platform;
        try {
            platform = SourceType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown platform " + raw);
        }
        if (platform == SourceType.MANUAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The MANUAL platform has no detected games");
        }
        return platform;
    }

    // ---- Views

    private GameMappingDto toDto(GameMapping mapping, UserAccount admin) {
        return toDtos(List.of(mapping), admin).getFirst();
    }

    /** The list rows of these mappings; {@code admin}, when given, saves looking the acting admin up. */
    private List<GameMappingDto> toDtos(List<GameMapping> mappings, UserAccount admin) {
        if (mappings.isEmpty()) return List.of();
        List<GameKey> games = mappings.stream().map(m -> GameKey.of(m.getSourceType(), m.getSourceId())).toList();
        Map<GameKey, String> names = mostFrequentNames(games);
        Map<GameKey, Long> followers = new HashMap<>();
        for (Object[] row : bindingRepository.countByGameAndStatus(platforms(games), ids(games), Status.MAPPED)) {
            followers.put(GameKey.of(row[0], row[1]), ((Number) row[2]).longValue());
        }
        Map<UUID, String> logins = new HashMap<>();
        if (admin != null) logins.put(admin.getId(), admin.getTwitchUsername());
        Set<UUID> unknown = new HashSet<>();
        mappings.stream().map(GameMapping::getUpdatedBy).filter(Objects::nonNull)
                .filter(u -> !logins.containsKey(u)).forEach(unknown::add);
        if (!unknown.isEmpty()) {
            userAccountRepository.findAllById(unknown).forEach(u -> logins.put(u.getId(), u.getTwitchUsername()));
        }
        return mappings.stream().map(m -> {
            GameKey key = GameKey.of(m.getSourceType(), m.getSourceId());
            return new GameMappingDto(m.getId(), m.getSourceType().name(), m.getSourceId(), names.get(key),
                    m.getTwitchGameId(), m.getTwitchGameName(),
                    m.isCclsOverridden() ? Set.copyOf(m.getCcls()) : null,
                    m.isTwsOverridden() ? Set.copyOf(m.getTws()) : null,
                    followers.getOrDefault(key, 0L), m.getUpdatedAt(),
                    m.getUpdatedBy() == null ? null : logins.get(m.getUpdatedBy()));
        }).toList();
    }

    private static DetectedGameDto detectedGame(Object[] row, Map<GameKey, String> names) {
        GameKey key = GameKey.of(row[0], row[1]);
        return new DetectedGameDto(key.sourceType(), key.sourceId(), names.get(key), ((Number) row[2]).longValue());
    }

    /** These games' platforms, for the aggregates to stay on the (source_type, source_id) index. */
    private static Set<SourceType> platforms(Collection<GameKey> games) {
        return games.stream().map(g -> SourceType.valueOf(g.sourceType())).collect(Collectors.toSet());
    }

    private static Set<String> ids(Collection<GameKey> games) {
        return games.stream().map(GameKey::sourceId).collect(Collectors.toSet());
    }

    /** Each game's most detected name (the first alphabetically on a tie), for these games. */
    private Map<GameKey, String> mostFrequentNames(Collection<GameKey> games) {
        if (games.isEmpty()) return Map.of();
        Map<GameKey, String> names = new HashMap<>();
        Map<GameKey, Long> best = new HashMap<>();
        for (Object[] row : bindingRepository.countSourceNames(platforms(games), ids(games))) {
            GameKey key = GameKey.of(row[0], row[1]);
            String name = (String) row[2];
            long count = ((Number) row[3]).longValue();
            Long current = best.get(key);
            if (current == null || count > current || (count == current && name.compareTo(names.get(key)) < 0)) {
                best.put(key, count);
                names.put(key, name);
            }
        }
        return names;
    }

    private static int pageSize(int size) {
        return Math.clamp(size, 1, MAX_PAGE_SIZE);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
