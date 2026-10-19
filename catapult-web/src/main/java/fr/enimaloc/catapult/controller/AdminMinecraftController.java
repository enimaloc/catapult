package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The admin Keys tab's Minecraft service accounts: enable / disable, reset the friend limit and
 * delete (409 while users are linked to it), each answered with catapult-api's status (502 when
 * unreachable); and the Microsoft device-code flow catapult-api runs, which this only relays: the
 * device code (else catapult-api's status and {@code message}, 502 when unreachable), then each create / reauth poll with catapult-api's status
 * (202 not confirmed yet, 200 done with the account's {@code minecraftUsername} and {@code label},
 * else the error and its {@code message} when catapult-api gave one).
 */
@RestController
@RequestMapping("/admin/minecraft-accounts")
@RequiredArgsConstructor
public class AdminMinecraftController {
    private final ApiService apiService;
    private final ModelFiller filler;

    public record EnabledBody(Boolean enabled) {}
    public record CreateBody(String deviceCode, String label) {}
    public record ReauthBody(String deviceCode) {}

    @PostMapping("/{id}/enabled")
    public ResponseEntity<Void> enabled(@PathVariable UUID id, @RequestBody EnabledBody body) {
        AdminAccess.require(filler);
        if (body.enabled() == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.status(apiService.adminPatchMinecraftAccount(id, Map.of("enabled", body.enabled()))).build();
    }

    @PostMapping("/{id}/reset-limit")
    public ResponseEntity<Void> resetLimit(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminPatchMinecraftAccount(id, Map.of("friendLimitReached", false))).build();
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminDeleteMinecraftAccount(id)).build();
    }

    /** The code (200), else catapult-api's status with its {@code message} when it gave one: Microsoft's refusal must reach the admin. */
    @PostMapping("/device-code")
    public ResponseEntity<?> deviceCode() {
        AdminAccess.require(filler);
        ApiService.MinecraftDeviceCodeStart start = apiService.adminMinecraftDeviceCode();
        if (start.status() == 200 && start.code() != null) return ResponseEntity.ok(start.code());
        int status = start.status() == 200 ? HttpStatus.BAD_GATEWAY.value() : start.status();
        return ResponseEntity.status(status).body(message(start.message()));
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateBody body) {
        AdminAccess.require(filler);
        if (blank(body.deviceCode())) return ResponseEntity.badRequest().build();
        String label = blank(body.label()) ? null : body.label().trim();
        return poll(() -> apiService.adminCreateMinecraftAccount(body.deviceCode(), label));
    }

    @PostMapping("/{id}/reauth")
    public ResponseEntity<Map<String, Object>> reauth(@PathVariable UUID id, @RequestBody ReauthBody body) {
        AdminAccess.require(filler);
        if (blank(body.deviceCode())) return ResponseEntity.badRequest().build();
        return poll(() -> apiService.adminReauthMinecraftAccount(id, body.deviceCode()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** catapult-api's status, with only what the page shows: the account once done, the error's message otherwise. */
    private static ResponseEntity<Map<String, Object>> poll(Supplier<ApiClient.Result<Map<String, Object>>> call) {
        ApiClient.Result<Map<String, Object>> result = call.get();
        Map<String, Object> answer = result.body() == null ? Map.of() : result.body();
        Map<String, Object> shown = new HashMap<>();
        if (result.status() == 200) {
            shown.put("minecraftUsername", answer.get("minecraftUsername"));
            shown.put("label", answer.get("label"));
        } else if (result.status() != 202) {
            shown.putAll(message(answer.get("message")));
        }
        return ResponseEntity.status(result.status()).body(shown);
    }

    /** {@code {message}} for a non-blank message, else nothing. */
    private static Map<String, Object> message(Object message) {
        return message instanceof String text && !text.isBlank() ? Map.of("message", text) : Map.of();
    }
}
