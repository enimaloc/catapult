package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.chat.ChatCommandEvent;
import fr.enimaloc.catapult.chat.ChatCommandPresetCatalog;
import fr.enimaloc.catapult.chat.PlaceholderResolver;
import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.ChatCommandFallback;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.event.ChatCommandDefinitionChangedEvent;
import fr.enimaloc.catapult.repository.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.ExperimentService;
import fr.enimaloc.catapult.service.SystemTwitchAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin REST endpoints for managing chat command definitions owned by the current user.
 *
 * <p>All endpoints are gated by the {@code chat.commands} experiment.</p>
 */
@RestController
@RequestMapping("/api/chat-commands")
@RequiredArgsConstructor
public class ApiAdminChatCommandsController {

    public static final String EXPERIMENT_KEY = "chat.commands";

    private static final Set<String> RESERVED_NAMES = Set.of("!setgame");

    private final ChatCommandDefinitionRepository repository;
    private final ChatCommandPresetCatalog catalog;
    private final PlaceholderResolver placeholderResolver;
    private final ExperimentService experimentService;
    private final SystemTwitchAccountService systemAccount;
    private final UserAccountRepository userRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final List<fr.enimaloc.catapult.chat.ChatCommand> staticCommands;

    public record FallbackDto(String placeholder, String fallbackText) {}

    public record CommandDto(
        UUID id,
        String name,
        String template,
        ChatCommandEvent.SenderRole permission,
        boolean enabled,
        String presetKey,
        List<FallbackDto> fallbacks
    ) {
        static CommandDto fromEntity(ChatCommandDefinition d) {
            List<FallbackDto> fb = d.getFallbacks().stream()
                .map(f -> new FallbackDto(f.getPlaceholder(), f.getFallbackText()))
                .toList();
            return new CommandDto(d.getId(), d.getName(), d.getTemplate(),
                d.getPermission(), d.isEnabled(), d.getPresetKey(), fb);
        }
    }

    public record BotModStatusDto(boolean modded, Instant checkedAt) {}

    /** Read-only view on a Java-coded ChatCommand (not editable by the streamer). */
    public record BuiltinDto(String name, ChatCommandEvent.SenderRole permission) {}

    public record ListResponse(
        List<String> presets,
        List<CommandDto> commands,
        List<BuiltinDto> builtins,
        BotModStatusDto botModStatus
    ) {}

    public record UpsertRequest(
        @NotNull
        @Pattern(regexp = "^![a-z][a-z0-9_]{0,30}$", message = "name must match ^![a-z][a-z0-9_]{0,30}$")
        String name,
        @NotNull @Size(max = 500) String template,
        @NotNull ChatCommandEvent.SenderRole permission,
        boolean enabled,
        Map<String, String> fallbacks
    ) {}

    @GetMapping
    @Transactional(readOnly = true)
    public ListResponse list(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = currentUser(jwt);
        gate(user);
        List<CommandDto> commands = repository.findByUser(user).stream()
            .map(CommandDto::fromEntity).toList();
        SystemTwitchAccountService.BotModStatus s = checkBotMod(user);
        List<BuiltinDto> builtins = staticCommands.stream()
            .map(c -> new BuiltinDto(c.getName(), c.getRequiredPermission()))
            .sorted((a, b) -> a.name().compareTo(b.name()))
            .toList();
        return new ListResponse(
            new ArrayList<>(catalog.allKeys()),
            commands,
            builtins,
            new BotModStatusDto(s.modded(), s.checkedAt())
        );
    }

    @PostMapping("/presets/{presetKey}")
    @Transactional
    public ResponseEntity<CommandDto> instantiatePreset(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String presetKey
    ) {
        UserAccount user = currentUser(jwt);
        gate(user);
        try {
            ChatCommandDefinition def = catalog.instantiate(user, presetKey, Locale.FRANCE);
            publishChanged(user);
            return ResponseEntity.status(HttpStatus.CREATED).body(CommandDto.fromEntity(def));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping
    @Transactional
    public ResponseEntity<CommandDto> create(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody UpsertRequest req
    ) {
        UserAccount user = currentUser(jwt);
        gate(user);
        validateTemplate(req.template());
        if (isReservedName(req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reserved command name");
        }
        if (repository.existsByUserAndName(user, req.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Name already used");
        }
        ChatCommandDefinition def = new ChatCommandDefinition();
        def.setUser(user);
        applyRequest(def, req);
        repository.save(def);
        publishChanged(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(CommandDto.fromEntity(def));
    }

    @PutMapping("/{id}")
    @Transactional
    public CommandDto update(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody UpsertRequest req
    ) {
        UserAccount user = currentUser(jwt);
        gate(user);
        validateTemplate(req.template());
        ChatCommandDefinition def = repository.findById(id)
            .filter(d -> d.getUser().getId().equals(user.getId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        applyRequest(def, req);
        repository.save(def);
        publishChanged(user);
        return CommandDto.fromEntity(def);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        UserAccount user = currentUser(jwt);
        gate(user);
        ChatCommandDefinition def = repository.findById(id)
            .filter(d -> d.getUser().getId().equals(user.getId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repository.delete(def);
        publishChanged(user);
        return ResponseEntity.noContent().build();
    }

    private UserAccount currentUser(Jwt jwt) {
        String twitchId = jwt.getClaimAsString("twitchId");
        return userRepo.findByTwitchId(twitchId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private void gate(UserAccount user) {
        // evaluateGate (not isRolledOut) so that an admin override on the user
        // materialises the assignment on first call. isRolledOut only reads
        // existing assignments, so overrides without a prior assignment would
        // never let the user reach this page.
        if (!experimentService.evaluateGate(user, EXPERIMENT_KEY)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Feature not enabled");
        }
    }

    /**
     * Best-effort bot mod status check. We do not have the streamer's decrypted OAuth token
     * available in the controller — the full mod-status check is exercised lazily at
     * sendMessage time. We pass an empty token here; {@link SystemTwitchAccountService#check}
     * is expected to gracefully fall back to "not modded" on auth failure and cache it.
     */
    private SystemTwitchAccountService.BotModStatus checkBotMod(UserAccount user) {
        try {
            return systemAccount.check(user, "");
        } catch (Exception e) {
            return new SystemTwitchAccountService.BotModStatus(false, Instant.now());
        }
    }

    private boolean isReservedName(String name) {
        return RESERVED_NAMES.contains(name);
    }

    private void validateTemplate(String template) {
        Set<String> unknown = placeholderResolver.findUnknownPaths(template);
        if (!unknown.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown placeholders: " + String.join(", ", unknown));
        }
    }

    private void applyRequest(ChatCommandDefinition def, UpsertRequest req) {
        def.setName(req.name());
        def.setTemplate(req.template());
        def.setPermission(req.permission());
        def.setEnabled(req.enabled());

        def.getFallbacks().clear();
        if (req.fallbacks() != null) {
            req.fallbacks().forEach((path, text) -> {
                if (text != null && !text.isBlank()) {
                    ChatCommandFallback fb = new ChatCommandFallback();
                    fb.setCommand(def);
                    fb.setPlaceholder(path);
                    fb.setFallbackText(text.length() > 200 ? text.substring(0, 200) : text);
                    def.getFallbacks().add(fb);
                }
            });
        }
    }

    private void publishChanged(UserAccount user) {
        eventPublisher.publishEvent(new ChatCommandDefinitionChangedEvent(this, user.getId()));
    }
}
