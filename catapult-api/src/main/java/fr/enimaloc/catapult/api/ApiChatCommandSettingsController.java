package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.ChatCommandSetting;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandSettingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * REST endpoints for a streamer's own free-form {@code key=value} chat-command settings
 * (see {@code ChatCommandSetting}, {@code SettingGetExpr}) — global to the user, not per-command.
 * Gated by the same {@code chat.commands} experiment as the rest of the chat-command API.
 */
@RestController
@RequestMapping("/api/chat-command-settings")
@RequiredArgsConstructor
public class ApiChatCommandSettingsController {

    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    // JsCompiler rejects these as ctx.settings.<key> segments (prototype-pollution guard) — reject
    // them here too, so a command referencing such a key fails at save time with a clear message
    // instead of compiling fine here and only failing later when the command actually runs.
    private static final Set<String> RESERVED_KEYS = Set.of("__proto__", "constructor", "prototype");

    // Presets (e.g. chat.preset.description.template) read ctx.settings.language to pick the
    // locale IGDB/Steam lookups respond in. Nothing in UserAccount/UserSettings/the JWT stores a
    // streamer's language, so the request's own resolved Locale (Accept-Language, the same signal
    // ApiClient already forwards browser-side) is the only thing to seed a sensible default from.
    private static final String LANGUAGE_KEY = "language";

    private final ChatCommandSettingRepository repository;
    private final UserAccountRepository userAccountRepository;
    private final ExperimentService experimentService;

    public record SettingDto(String key, String value) {
        static SettingDto fromEntity(ChatCommandSetting s) {
            return new SettingDto(s.getKey(), s.getValue());
        }
    }

    public record UpsertRequest(String value) {}

    @GetMapping
    public List<SettingDto> list(@AuthenticationPrincipal Jwt jwt, Locale locale) {
        UserAccount user = currentUser(jwt);
        gate(user);
        ensureDefaultLanguage(user, locale);
        return repository.findByUser(user).stream().map(SettingDto::fromEntity).toList();
    }

    /** Seeds a default {@code language} setting on first visit from the caller's resolved locale
     *  — idempotent, like {@code ChatCommandPresetCatalog#ensureBuiltins}, so a manually
     *  deleted/edited row stays that way. */
    private void ensureDefaultLanguage(UserAccount user, Locale locale) {
        if (repository.findByUserAndKey(user, LANGUAGE_KEY).isPresent()) {
            return;
        }
        ChatCommandSetting setting = new ChatCommandSetting();
        setting.setUser(user);
        setting.setKey(LANGUAGE_KEY);
        setting.setValue(locale.getLanguage());
        repository.save(setting);
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> upsert(@AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                       @RequestBody UpsertRequest body) {
        UserAccount user = currentUser(jwt);
        gate(user);
        if (!VALID_KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid key (must match ^[A-Za-z_][A-Za-z0-9_]*$): " + key);
        }
        if (RESERVED_KEYS.contains(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reserved key: " + key);
        }
        ChatCommandSetting setting = repository.findByUserAndKey(user, key).orElseGet(() -> {
            ChatCommandSetting s = new ChatCommandSetting();
            s.setUser(user);
            s.setKey(key);
            return s;
        });
        setting.setValue(body.value());
        repository.save(setting);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        UserAccount user = currentUser(jwt);
        gate(user);
        repository.deleteByUserAndKey(user, key);
        return ResponseEntity.noContent().build();
    }

    private UserAccount currentUser(Jwt jwt) {
        String twitchId = jwt.getClaimAsString("twitchId");
        return userAccountRepository.findByTwitchId(twitchId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private void gate(UserAccount user) {
        if (!experimentService.evaluateGate(user, ApiChatCommandsController.EXPERIMENT_KEY)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Feature not enabled");
        }
    }
}
