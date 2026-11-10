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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stream title sent with a category, behind experiment {@value #EXPERIMENT_KEY}: the game's own title, else the
 * channel-wide template, {@code {{GAME}}} standing for the category's name and {@code {{TITLE}}} for the title the
 * stream has before the change (any case, spaces inside the braces allowed; another name is left as typed). Nothing
 * while the streamer hasn't turned it on, so their title is left alone.
 */
@Service
@RequiredArgsConstructor
public class StreamTitleService {

    public static final String EXPERIMENT_KEY = "stream.title";
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*(GAME|TITLE)\\s*}}", Pattern.CASE_INSENSITIVE);
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

    /**
     * The title to send with {@code binding}'s category, empty when none is to be sent; {@code currentTitle} is only
     * asked for when the template uses {@code {{TITLE}}}.
     */
    public Optional<String> titleFor(UserAccount user, UserSettings settings, GameBinding binding,
                                     Supplier<Optional<String>> currentTitle) {
        if (!sendsTitles(user, settings)) return Optional.empty();
        String template = hasText(binding.getTitle()) ? binding.getTitle() : settings.getTitleTemplate();
        return render(template, binding.getTwitchGameName(), currentTitle);
    }

    /** The title to send with the "no game" category, from the channel-wide template only. */
    public Optional<String> titleForNoGame(UserAccount user, UserSettings settings,
                                           Supplier<Optional<String>> currentTitle) {
        if (!sendsTitles(user, settings)) return Optional.empty();
        return render(settings.getTitleTemplate(), settings.getNoGameTwitchGameName(), currentTitle);
    }

    /**
     * {@code template} with its variables replaced, trimmed and cut to Twitch's limit; empty when blank, or when it
     * uses {@code {{TITLE}}} and the current title can't be read (better left alone than half-rewritten).
     */
    public static Optional<String> render(String template, String gameName, Supplier<Optional<String>> currentTitle) {
        if (!hasText(template)) return Optional.empty();
        Matcher variables = VARIABLE.matcher(template);
        Optional<String> current = Optional.empty();
        boolean currentRead = false;
        StringBuilder rendered = new StringBuilder();
        while (variables.find()) {
            String value;
            if ("GAME".equalsIgnoreCase(variables.group(1))) {
                value = gameName == null ? "" : gameName;
            } else {
                if (!currentRead) {
                    current = currentTitle.get();
                    currentRead = true;
                }
                if (current.isEmpty()) return Optional.empty();
                value = current.get();
            }
            variables.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        variables.appendTail(rendered);
        String title = rendered.toString().strip();
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
