package fr.enimaloc.catapult.api.chatcommand;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.chatcommand.ChatCommandSettingUpsertRequest;
import fr.enimaloc.catapult.common.dto.chatcommand.SettingDto;
import fr.enimaloc.catapult.service.chatcommand.ChatCommandSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * REST endpoints for a streamer's own free-form {@code key=value} chat-command settings
 * (see {@code ChatCommandSetting}, {@code SettingGetExpr}) — global to the user, not per-command.
 * Gated by the same {@code chat.commands} experiment as the rest of the chat-command API.
 */
@RestController
@RequestMapping(ApiPaths.USER + "/chat-command-settings")
@RequiredArgsConstructor
public class ApiChatCommandSettingsController {

    private final ChatCommandSettingService settings;
    private final ApiUserResolver userResolver;

    @PreAuthorize("@authz.isSelf(#userId)")
    @GetMapping
    public List<SettingDto> list(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, Locale locale) {
        return settings.list(userResolver.viewerByTwitchId(jwt), locale);
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @PutMapping("/{key}")
    public ResponseEntity<Void> upsert(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, @PathVariable String key,
                       @RequestBody ChatCommandSettingUpsertRequest body) {
        settings.upsert(userResolver.viewerByTwitchId(jwt), key, body.value());
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@authz.isSelf(#userId)")
    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable UUID userId, @AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        settings.delete(userResolver.viewerByTwitchId(jwt), key);
        return ResponseEntity.noContent().build();
    }
}
