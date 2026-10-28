package fr.enimaloc.catapult.service.degraded;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;

/**
 * The degraded banner's effect labels: every {@code degraded.effect.<property>} message but
 * {@code degraded.effect.rest}, keyed by the property catapult-api reports it under. A property without one
 * is not shown, so wording a new effect of the degraded profile only takes its messages.
 * The messages are enumerated from the site's own bundle ({@code spring.messages.basename}): a locale without
 * its file falls back on the default one, never on the server's locale. Java reads the files as UTF-8.
 */
@Component
public class DegradedLabels {
    private static final String PREFIX = "degraded.effect.";
    private static final String REST = PREFIX + "rest";
    private static final ResourceBundle.Control NO_SERVER_LOCALE =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private final String basename;

    @Autowired
    public DegradedLabels(@Value("${spring.messages.basename}") String basename) {
        this.basename = basename;
    }

    /** Property → label, its value left as {@code {0}} for degraded.js to fill. */
    public Map<String, String> labels(Locale locale) {
        ResourceBundle bundle = ResourceBundle.getBundle(basename, locale, NO_SERVER_LOCALE);
        Map<String, String> labels = new TreeMap<>();
        for (String key : bundle.keySet()) {
            if (!key.startsWith(PREFIX) || key.equals(REST)) continue;
            // Formatted as MessageSource would, so that '' reads as an apostrophe
            String label = new MessageFormat(bundle.getString(key), locale).format(new Object[]{"{0}"});
            labels.put(key.substring(PREFIX.length()), label);
        }
        return labels;
    }
}
