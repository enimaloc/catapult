package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandDefinition;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ChatCommandPresetCatalog {

    record Preset(String key, ChatCommandEvent.SenderRole defaultPermission) {}

    private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put("game",        new Preset("game",        ChatCommandEvent.SenderRole.VIEWERS));
        PRESETS.put("description", new Preset("description", ChatCommandEvent.SenderRole.VIEWERS));
        PRESETS.put("store",       new Preset("store",       ChatCommandEvent.SenderRole.VIEWERS));
        PRESETS.put("release",     new Preset("release",     ChatCommandEvent.SenderRole.VIEWERS));
        PRESETS.put("igdb",        new Preset("igdb",        ChatCommandEvent.SenderRole.VIEWERS));
        PRESETS.put("triggers",    new Preset("triggers",    ChatCommandEvent.SenderRole.VIEWERS));
        // Data-driven since the migration that removed SetGameCommand (the last Java-backed
        // command with an editable template) — matches its old hardcoded default permission.
        PRESETS.put("setgame",     new Preset("setgame",     ChatCommandEvent.SenderRole.MODERATOR));
    }

    public static final String BUILTIN_PRESET_KEY_PREFIX = "builtin:";

    private final MessageSource messageSource;
    private final ChatCommandDefinitionRepository repository;
    private final List<ChatCommand> staticCommands;

    public static boolean isBuiltin(ChatCommandDefinition def) {
        return def.getPresetKey() != null && def.getPresetKey().startsWith(BUILTIN_PRESET_KEY_PREFIX);
    }

    /**
     * The i18n identifier tied to a Java command. The resolved key is
     * {@code chat.builtin.<command-without-bang>.template}.
     */
    private static String builtinTemplateKey(String commandName) {
        String stripped = commandName.startsWith("!") ? commandName.substring(1) : commandName;
        return "chat.builtin." + stripped + ".template";
    }

    public Set<String> allKeys() {
        return PRESETS.keySet();
    }

    public ChatCommandDefinition instantiate(UserAccount user, String presetKey, Locale locale) {
        Preset preset = PRESETS.get(presetKey);
        if (preset == null) {
            throw new IllegalArgumentException("Unknown preset: " + presetKey);
        }
        String name = messageSource.getMessage("chat.preset." + presetKey + ".name", null, locale);
        String template = messageSource.getMessage("chat.preset." + presetKey + ".template", null, locale);

        if (repository.existsByUserAndName(user, name)) {
            throw new IllegalStateException("Preset already instantiated: " + name);
        }

        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setUser(user);
        def.setName(name);
        def.setTemplate(template);
        def.setPermission(preset.defaultPermission());
        def.setEnabled(true);
        def.setPresetKey(presetKey);
        return repository.save(def);
    }

    /**
     * Pre-registers the data-driven presets (game, description, store...) for {@code user},
     * disabled by default, so the user sees every preconfigured command right away and only
     * has to enable them.
     * Idempotent: skips the presets that already have a definition (same name).
     * Call it only on the very first display of the page (empty list), to respect any
     * manual deletion afterwards.
     */
    public void bootstrapPresetsDisabled(UserAccount user, Locale locale) {
        for (Map.Entry<String, Preset> entry : PRESETS.entrySet()) {
            String presetKey = entry.getKey();
            Preset preset = entry.getValue();
            String name = messageSource.getMessage("chat.preset." + presetKey + ".name", null, locale);
            if (repository.existsByUserAndName(user, name)) continue;

            String template = messageSource.getMessage("chat.preset." + presetKey + ".template", null, locale);
            ChatCommandDefinition def = new ChatCommandDefinition();
            def.setUser(user);
            def.setName(name);
            def.setTemplate(template);
            def.setPermission(preset.defaultPermission());
            def.setEnabled(false);
            def.setPresetKey(presetKey);
            repository.save(def);
        }
    }

    /**
     * Makes sure a ChatCommandDefinition row exists for each Java (built-in) command of
     * {@code user}. Call it on every visit: the built-ins evolve as new {@link ChatCommand}
     * beans are added to the code, and deleting a built-in makes no sense (the Java action
     * stays).
     */
    public void ensureBuiltins(UserAccount user, Locale locale) {
        for (ChatCommand cmd : staticCommands) {
            // Owner-only commands (e.g. !debug) stay out of the streamer UI:
            // no definition = neither visible nor disableable.
            if (cmd.isOwnerOnly()) continue;
            String name = cmd.getName();
            if (repository.existsByUserAndName(user, name)) continue;
            String template = messageSource.getMessage(builtinTemplateKey(name), null, locale);
            ChatCommandDefinition def = new ChatCommandDefinition();
            def.setUser(user);
            def.setName(name);
            def.setTemplate(template);
            def.setPermission(cmd.getRequiredPermission());
            def.setEnabled(false); // disabled by default, like the presets
            def.setPresetKey(BUILTIN_PRESET_KEY_PREFIX + name.replaceFirst("^!", ""));
            repository.save(def);
        }
    }
}
