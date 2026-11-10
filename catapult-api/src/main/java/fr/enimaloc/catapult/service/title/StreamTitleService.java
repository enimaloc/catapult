package fr.enimaloc.catapult.service.title;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.title.StreamTitleState;
import fr.enimaloc.catapult.repository.title.StreamTitleStateRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The stream title sent with a category, behind experiment {@value #EXPERIMENT_KEY}: the binding's own title (a
 * game's, a custom binding's, a fallback's), else the channel-wide template. A template's variables are written
 * {@code {{NAME}}} exactly: {@code {{GAME}}} the game detected (a custom binding's name, nothing without a game),
 * {@code {{CATEGORY}}} the Twitch category's name, {@code {{TITLE}}} the title the stream has just before the change,
 * {@code {{ORIGINAL_TITLE}}} the one the streamer set themselves, before Catapult changed it; anything else (another
 * case, spaces, another name) stays as typed. Nothing while the streamer hasn't turned it on, so their title is left alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamTitleService {

    public static final String EXPERIMENT_KEY = "stream.title";
    /** Conditions ({@code {{COND ? [[…]] : [[…]]}}}) in the titles, within {@value #EXPERIMENT_KEY}. */
    public static final String CONDITIONS_KEY = "stream.title.conditions";
    /** The title's preview under its fields, within {@value #EXPERIMENT_KEY}. */
    public static final String PREVIEW_KEY = "stream.title.preview";
    /** Twitch refuses a longer title. */
    public static final int MAX_LENGTH = TitleTemplate.MAX_LENGTH;


    private final ExperimentService experimentService;
    private final StreamTitleStateRepository stateRepository;

    /**
     * A title to send, with the one the stream had before ({@code before}, empty when it couldn't be read), what
     * {@link #sent} remembers once Twitch took it.
     */
    public record PlannedTitle(String title, Optional<String> before) {}

    /** User-facing paths (settings page and save): may assign the channel to the experiment. */
    public boolean isAvailableFor(UserAccount user) {
        return experimentService.evaluateGate(user, EXPERIMENT_KEY);
    }

    /** Background paths (the channel update): read-only, never assigns. */
    public boolean isAvailableReadOnly(UserAccount user) {
        return experimentService.isRolledOut(user, EXPERIMENT_KEY);
    }

    /** Whether the titles sent for {@code user} read their conditions; read-only, never assigns. */
    public boolean conditionsAvailableReadOnly(UserAccount user) {
        return experimentService.isRolledOut(user, CONDITIONS_KEY);
    }

    /** Whether the settings page offers {@code user} the title's preview: may assign the channel. */
    public boolean previewAvailableFor(UserAccount user) {
        return experimentService.evaluateGate(user, PREVIEW_KEY);
    }

    /**
     * The title to send with {@code binding}'s category, empty when none is to be sent. {@code currentTitle} (the
     * stream's title on Twitch) is read once whenever a title is to be sent: it tells a title changed by hand.
     */
    public Optional<PlannedTitle> titleFor(UserAccount user, UserSettings settings, GameBinding binding,
                                           Supplier<Optional<String>> currentTitle) {
        if (!sendsTitles(user, settings)) return Optional.empty();
        String template = hasText(binding.getTitle()) ? binding.getTitle() : settings.getTitleTemplate();
        String game = binding.getGameName() != null ? binding.getGameName() : binding.getSourceName();
        return plan(user, template, game, binding.getTwitchGameName(), currentTitle);
    }

    /** The title to send with the "no game" category (its own, else the channel-wide one), no game being played. */
    public Optional<PlannedTitle> titleForNoGame(UserAccount user, UserSettings settings,
                                                 Supplier<Optional<String>> currentTitle) {
        if (!sendsTitles(user, settings)) return Optional.empty();
        String template = hasText(settings.getNoGameTitle()) ? settings.getNoGameTitle() : settings.getTitleTemplate();
        return plan(user, template, "", settings.getNoGameTwitchGameName(), currentTitle);
    }

    /**
     * Twitch took {@code planned}: it is the last title Catapult sent, and the one found before it, when it isn't the
     * last one Catapult sent, the streamer's own. A failure here is only logged: the title is on Twitch already.
     */
    public void sent(UserAccount user, PlannedTitle planned) {
        try {
            StreamTitleState state = stateRepository.findById(user.getId())
                    .orElseGet(() -> new StreamTitleState(user.getId()));
            planned.before()
                    .filter(before -> !before.equals(state.getLastSentTitle()))
                    .ifPresent(state::setOriginalTitle);
            state.setLastSentTitle(planned.title());
            stateRepository.save(state);
        } catch (RuntimeException e) {
            log.warn("Could not remember the title sent for user {}: {}", user.getId(), e.getMessage());
        }
    }

    private Optional<PlannedTitle> plan(UserAccount user, String template, String gameName, String categoryName,
                                        Supplier<Optional<String>> currentTitle) {
        if (!hasText(template)) return Optional.empty();
        Optional<String> before = currentTitle.get();
        Optional<String> original = before.map(current -> stateRepository.findById(user.getId())
                // Still the title Catapult sent last: the streamer's own is the one remembered
                .filter(state -> current.equals(state.getLastSentTitle()) && state.getOriginalTitle() != null)
                .map(StreamTitleState::getOriginalTitle)
                .orElse(current));
        return render(template, gameName, categoryName, before, original).map(title -> new PlannedTitle(title, before));
    }

    /**
     * {@code template} with its variables replaced, trimmed and cut to Twitch's limit; empty when blank, or when it
     * uses a title that couldn't be read (better left alone than half-rewritten).
     */
    public static Optional<String> render(String template, String gameName, String categoryName,
                                          Optional<String> title, Optional<String> originalTitle) {
        return TitleTemplate.render(template, new TitleContext(gameName, categoryName, () -> title,
                () -> originalTitle, java.util.Map.of(), null, null), false);
    }

    /**
     * A title or template as typed: trimmed, null when blank; longer than {@value #MAX_LENGTH} characters is a
     * 400, since it would never fit in a title.
     */
    public static String cleaned(String typed) {
        if (!hasText(typed)) return null;
        String title = typed.strip();
        if (title.codePointCount(0, title.length()) > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A title is at most " + MAX_LENGTH + " characters");
        }
        return title;
    }

    private boolean sendsTitles(UserAccount user, UserSettings settings) {
        return settings != null && settings.isTitleFeatureEnabled() && isAvailableReadOnly(user);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
