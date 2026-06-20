package fr.enimaloc.catapult.chat;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
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
        PRESETS.put("game",        new Preset("game",        ChatCommandEvent.SenderRole.EVERYONE));
        PRESETS.put("description", new Preset("description", ChatCommandEvent.SenderRole.EVERYONE));
        PRESETS.put("store",       new Preset("store",       ChatCommandEvent.SenderRole.EVERYONE));
        PRESETS.put("release",     new Preset("release",     ChatCommandEvent.SenderRole.EVERYONE));
        PRESETS.put("igdb",        new Preset("igdb",        ChatCommandEvent.SenderRole.EVERYONE));
    }

    public static final String BUILTIN_PRESET_KEY_PREFIX = "builtin:";

    private final MessageSource messageSource;
    private final ChatCommandDefinitionRepository repository;
    private final List<ChatCommand> staticCommands;

    public static boolean isBuiltin(ChatCommandDefinition def) {
        return def.getPresetKey() != null && def.getPresetKey().startsWith(BUILTIN_PRESET_KEY_PREFIX);
    }

    /**
     * Identifiant i18n associé à une commande Java. La clé résolue est
     * {@code chat.builtin.<commande-sans-bang>.template}.
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
     * Pré-enregistre tous les presets pour {@code user}, désactivés par défaut.
     * Idempotent : ignore les presets qui ont déjà une définition (même nom).
     * Appelé au premier affichage de la page côté liste afin que l'utilisateur
     * voie d'emblée toutes les commandes pré-configurées et n'ait qu'à les
     * activer.
     */
    public void bootstrapDisabled(UserAccount user, Locale locale) {
        // 1. Presets data-driven (désactivés par défaut).
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
        // 2. Commandes Java (built-in) : nom verrouillé, template editable,
        //    activé par défaut puisque la logique d'action existait déjà.
        for (ChatCommand cmd : staticCommands) {
            String name = cmd.getName();
            if (repository.existsByUserAndName(user, name)) continue;
            String template = messageSource.getMessage(builtinTemplateKey(name), null, locale);
            ChatCommandDefinition def = new ChatCommandDefinition();
            def.setUser(user);
            def.setName(name);
            def.setTemplate(template);
            def.setPermission(cmd.getRequiredPermission());
            def.setEnabled(true);
            def.setPresetKey(BUILTIN_PRESET_KEY_PREFIX + name.replaceFirst("^!", ""));
            repository.save(def);
        }
    }
}
