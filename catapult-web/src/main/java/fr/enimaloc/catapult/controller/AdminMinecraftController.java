package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MinecraftDeviceCodeDto;
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
 * device code (502 when it can't be had), then each create / reauth poll with catapult-api's status
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

    @PostMapping("/device-code")
    public ResponseEntity<MinecraftDeviceCodeDto> deviceCode() {
        AdminAccess.require(filler);
        MinecraftDeviceCodeDto code = apiService.adminMinecraftDeviceCode();
        return code == null ? ResponseEntity.status(HttpStatus.BAD_GATEWAY).build() : ResponseEntity.ok(code);
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
        } else if (result.status() != 202 && answer.get("message") instanceof String message && !message.isBlank()) {
            shown.put("message", message);
        }
        return ResponseEntity.status(result.status()).body(shown);
    }
}
