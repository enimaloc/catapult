package fr.enimaloc.catapult.service.title;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.account.UserSettings;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * The stream title sent with a category, behind experiment {@value #EXPERIMENT_KEY}: the game's own title, else the
 * channel-wide template, {@value #GAME_PLACEHOLDER} standing for the category's name. Nothing while the streamer
 * hasn't turned it on, so their title is left alone.
 */
@Service
@RequiredArgsConstructor
public class StreamTitleService {

    public static final String EXPERIMENT_KEY = "stream.title";
    public static final String GAME_PLACEHOLDER = "{game}";
    /** Twitch refuses a longer title. */
    public static final int MAX_LENGTH = 140;

    private final ExperimentService experimentService;

    /** User-facing paths (settings page and save): may assign the channel to the experiment. */
    public boolean isAvailableFor(UserAccount user) {
        return experimentService.evaluateGate(user, EXPERIMENT_KEY);
    }

    /** Background paths (the channel update): read-only, never assigns. */
    public boolean isAvailableReadOnly(UserAccount user) {
        return experimentService.isRolledOut(user, EXPERIMENT_KEY);
    }

    /** The title to send with {@code binding}'s category, empty when none is to be sent. */
    public Optional<String> titleFor(UserAccount user, UserSettings settings, GameBinding binding) {
        if (!sendsTitles(user, settings)) return Optional.empty();
        String template = hasText(binding.getTitle()) ? binding.getTitle() : settings.getTitleTemplate();
        return render(template, binding.getTwitchGameName());
    }

    /** The title to send with the "no game" category, from the channel-wide template only. */
    public Optional<String> titleForNoGame(UserAccount user, UserSettings settings) {
        if (!sendsTitles(user, settings)) return Optional.empty();
        return render(settings.getTitleTemplate(), settings.getNoGameTwitchGameName());
    }

    /** {@code template} with {@value #GAME_PLACEHOLDER} replaced, trimmed and cut to Twitch's limit; empty when blank. */
    public static Optional<String> render(String template, String gameName) {
        if (!hasText(template)) return Optional.empty();
        String title = template.replace(GAME_PLACEHOLDER, gameName == null ? "" : gameName).strip();
        if (title.codePointCount(0, title.length()) > MAX_LENGTH) {
            title = title.substring(0, title.offsetByCodePoints(0, MAX_LENGTH)).strip();
        }
        // Twitch refuses an empty title
        return title.isEmpty() ? Optional.empty() : Optional.of(title);
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
