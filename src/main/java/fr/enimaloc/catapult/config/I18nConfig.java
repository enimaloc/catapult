package fr.enimaloc.catapult.config;

import com.ibm.icu.text.MessageFormat;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class I18nConfig {

    @Bean
    public MessageSource messageSource() {
        return new IcuMessageSource("lang/messages");
    }

    /**
     * Minimal {@link MessageSource} backed by {@code ResourceBundle} files and
     * formatted with ICU4J, so CLDR plural rules work out of the box.
     *
     * <p>Missing keys are returned as the key itself (code-as-default-message).
     * Locale fallback uses the no-suffix base bundle (e.g. {@code messages.properties})
     * rather than the JVM default locale, matching Spring's own behaviour.
     */
    static class IcuMessageSource implements MessageSource {

        private final String basename;

        /**
         * ResourceBundle control that falls back to the base (root) bundle
         * instead of the JVM default locale, consistent with how
         * {@code ResourceBundleMessageSource} works in Spring.
         */
        private static final ResourceBundle.Control NO_FALLBACK_CONTROL =
                ResourceBundle.Control.getNoFallbackControl(
                        ResourceBundle.Control.FORMAT_PROPERTIES);

        /** Cache: (pattern, locale) → compiled ICU MessageFormat */
        private final Map<String, Map<Locale, MessageFormat>> fmtCache = new ConcurrentHashMap<>();

        IcuMessageSource(String basename) {
            this.basename = basename;
        }

        // ------------------------------------------------------------------ //
        //  MessageSource                                                       //
        // ------------------------------------------------------------------ //

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            String pattern = resolvePattern(code, locale);
            if (pattern == null) {
                pattern = (defaultMessage != null) ? defaultMessage : code;
            }
            return format(pattern, args, locale);
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) throws NoSuchMessageException {
            String pattern = resolvePattern(code, locale);
            if (pattern == null) {
                // code-as-default-message behaviour
                return code;
            }
            return format(pattern, args, locale);
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
            for (String code : resolvable.getCodes()) {
                String pattern = resolvePattern(code, locale);
                if (pattern != null) {
                    return format(pattern, resolvable.getArguments(), locale);
                }
            }
            String def = resolvable.getDefaultMessage();
            if (def != null) {
                return format(def, resolvable.getArguments(), locale);
            }
            throw new NoSuchMessageException(
                    resolvable.getCodes().length > 0 ? resolvable.getCodes()[0] : "(unknown)", locale);
        }

        // ------------------------------------------------------------------ //
        //  Internals                                                           //
        // ------------------------------------------------------------------ //

        /**
         * Look up the raw pattern for {@code code} in the appropriate bundle,
         * falling back from the requested locale → root bundle (no JVM-default
         * locale fallback).  Returns {@code null} if the key is absent.
         */
        private String resolvePattern(String code, Locale locale) {
            // 1. Try exact locale (e.g. messages_fr.properties)
            String result = tryBundle(locale, code);
            if (result != null) return result;

            // 2. Walk up the locale hierarchy (e.g. fr_CA → fr → root)
            List<Locale> candidates = NO_FALLBACK_CONTROL.getCandidateLocales(basename, locale);
            for (Locale candidate : candidates) {
                if (candidate.equals(locale)) continue; // already tried
                result = tryBundle(candidate, code);
                if (result != null) return result;
            }
            return null;
        }

        private String tryBundle(Locale locale, String code) {
            try {
                ResourceBundle bundle = ResourceBundle.getBundle(
                        basename, locale, NO_FALLBACK_CONTROL);
                if (bundle.containsKey(code)) {
                    return bundle.getString(code);
                }
            } catch (MissingResourceException ignored) {
                // bundle not found for this locale — fall through
            }
            return null;
        }

        private String format(String pattern, Object[] args, Locale locale) {
            if (args == null || args.length == 0) {
                return pattern;
            }
            Map<Locale, MessageFormat> byLocale =
                    fmtCache.computeIfAbsent(pattern, k -> new ConcurrentHashMap<>());
            MessageFormat fmt = byLocale.computeIfAbsent(locale,
                    l -> new MessageFormat(pattern, l));
            synchronized (fmt) {
                return fmt.format(args);
            }
        }
    }
}
