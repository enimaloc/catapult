package fr.enimaloc.catapult.service.title;

import fr.enimaloc.catapult.common.dto.channel.TitlePreviewRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.title.StreamTitleState;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.binding.CustomBindingRepository;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import fr.enimaloc.catapult.repository.title.StreamTitleStateRepository;
import fr.enimaloc.catapult.service.binding.GameBindingLookup;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.category.CategoryChain;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

/**
 * A title field's text rendered as it would be sent now, for its preview: with the context of what it is the title of
 * (a game, a custom binding, a fallback, else the game played now or an example) and the conditions of
 * {@link StreamTitleService#context}. The titles before are the last ones Catapult knows, Twitch isn't asked.
 */
@Service
@RequiredArgsConstructor
public class TitlePreviewService {

    /** The game a channel-wide template is previewed with while none is played. */
    public static final String EXAMPLE_GAME = "Hades II";
    private static final String EXAMPLE_CATEGORY = "Hades II";

    private final StreamTitleService streamTitleService;
    private final StreamTitleStateRepository stateRepository;
    private final GameBindingRepository gameBindingRepository;
    private final CustomBindingRepository customBindingRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final GameStateService gameStateService;
    private final GameBindingLookup gameBindingLookup;

    /**
     * {@code request}'s text rendered; empty when no title would be sent (blank, or a title it uses is unknown). A
     * text longer than a title is a 400, as when it is saved: never rendered, whatever its nesting.
     */
    @Transactional(readOnly = true)
    public Optional<String> preview(UserAccount user, TitlePreviewRequest request) {
        String typed = StreamTitleService.cleaned(request.template());
        UserSettings settings = userSettingsRepository.findById(user.getId()).orElseGet(UserSettings::new);
        String kind = request.kind() == null ? "TEMPLATE" : request.kind();
        // An empty own title sends the channel-wide one, as StreamTitleService.titleFor does
        String template = typed != null || "TEMPLATE".equals(kind) ? typed : settings.getTitleTemplate();
        boolean noGame = "NO_GAME".equals(kind);
        GameBinding binding = noGame ? null : bindingFor(user, settings, kind, request);
        Optional<String> lastSent = stateRepository.findById(user.getId()).map(StreamTitleState::getLastSentTitle);
        TitleContext context = streamTitleService.context(user, settings, binding, noGame,
                labelSent(settings, binding, noGame), lastSent);
        return TitleTemplate.render(template, context, streamTitleService.conditionsAvailableReadOnly(user));
    }

    private GameBinding bindingFor(UserAccount user, UserSettings settings, String kind, TitlePreviewRequest request) {
        return switch (kind) {
            case "BINDING" -> request.bindingId() == null ? example()
                    : gameBindingRepository.findByIdAndUser(request.bindingId(), user).orElseGet(TitlePreviewService::example);
            case "CUSTOM" -> request.customBindingId() == null ? example()
                    : customBindingRepository.findByIdAndUser(request.customBindingId(), user)
                    .map(custom -> custom.toGameBinding(user)).orElseGet(TitlePreviewService::example);
            case "INCOMPLETE" -> incomplete(user, settings);
            default -> playedNow(user).orElseGet(TitlePreviewService::example);
        };
    }

    private Optional<GameBinding> playedNow(UserAccount user) {
        return gameStateService.getLastKnownGame(user)
                .flatMap(detected -> gameBindingLookup.findByDetectedGame(user, detected));
    }

    /** The incomplete fallback, its {@code {{GAME}}} the game played now (an example without one). */
    private GameBinding incomplete(UserAccount user, UserSettings settings) {
        GameBinding fallback = new GameBinding();
        fallback.setSourceName(CategoryChain.INCOMPLETE_FALLBACK_SOURCE);
        fallback.setTwitchGameId(settings.getIncompleteFallbackTwitchGameId());
        fallback.setTwitchGameName(settings.getIncompleteFallbackTwitchGameName());
        fallback.setTitle(settings.getIncompleteFallbackTitle());
        fallback.setGameName(gameStateService.getLastKnownGame(user).map(g -> g.getSourceName()).orElse(EXAMPLE_GAME));
        return fallback;
    }

    private static GameBinding example() {
        GameBinding example = new GameBinding();
        example.setSourceName(EXAMPLE_GAME);
        example.setTwitchGameName(EXAMPLE_CATEGORY);
        return example;
    }

    /** Whether a content label would go out with it, as the channel update builds its payload. */
    private static boolean labelSent(UserSettings settings, GameBinding binding, boolean noGame) {
        if (!settings.isCclFeatureEnabled()) return false;
        if (noGame) return !settings.getNoGameCcls().isEmpty();
        if (binding == null || !binding.isCclEnabled()) return false;
        Set<String> blocked = binding.getId() == null ? Set.of() : settings.getBlockedCcls();
        return binding.getCcls().stream().anyMatch(ccl -> !blocked.contains(ccl));
    }
}
