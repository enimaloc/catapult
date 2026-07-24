package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.ChatCommandParam;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.ChatCommandParamRepository;
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
import java.util.regex.Pattern;

/**
 * REST endpoints for a streamer's own free-form {@code key=value} chat-command params
 * (see {@code ChatCommandParam}, {@code ParamGetExpr}) — global to the user, not per-command.
 * Gated by the same {@code chat.commands} experiment as the rest of the chat-command API.
 */
@RestController
@RequestMapping("/api/chat-command-params")
@RequiredArgsConstructor
public class ApiChatCommandParamsController {

    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final ChatCommandParamRepository repository;
    private final UserAccountRepository userAccountRepository;
    private final ExperimentService experimentService;

    public record ParamDto(String key, String value) {
        static ParamDto fromEntity(ChatCommandParam p) {
            return new ParamDto(p.getKey(), p.getValue());
        }
    }

    public record UpsertRequest(String value) {}

    @GetMapping
    public List<ParamDto> list(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = currentUser(jwt);
        gate(user);
        return repository.findByUser(user).stream().map(ParamDto::fromEntity).toList();
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
        ChatCommandParam param = repository.findByUserAndKey(user, key).orElseGet(() -> {
            ChatCommandParam p = new ChatCommandParam();
            p.setUser(user);
            p.setKey(key);
            return p;
        });
        param.setValue(body.value());
        repository.save(param);
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
