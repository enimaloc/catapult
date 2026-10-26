package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.AccountDto;
import fr.enimaloc.catapult.service.minecraft.MinecraftServiceAccountAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/minecraft-accounts")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("minecraft.enabled")
public class ApiAdminMinecraftAccountsController {

    private final MinecraftServiceAccountAdmin accounts;

    @GetMapping
    public List<AccountDto> list() {
        return accounts.list();
    }

    @PostMapping("/device-code")
    public ResponseEntity<?> startDeviceCode() {
        return ResponseEntity.ok(accounts.startDeviceCode());
    }

    /** 202 tant que l'admin n'a pas validé le code sur microsoft.com/link ; 200 + compte créé ensuite. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        return accounts.create(body.get("deviceCode"), body.getOrDefault("label", "Compte Minecraft"))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(ApiAdminMinecraftAccountsController::pending);
    }

    /** Re-runs the device-code flow for an account whose MSA refresh token expired; 202 until it is validated. */
    @PostMapping("/{id}/reauth")
    public ResponseEntity<?> reauth(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return accounts.reauth(id, body.get("deviceCode"))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(ApiAdminMinecraftAccountsController::pending);
    }

    @PatchMapping("/{id}")
    public AccountDto update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return accounts.update(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id) {
        accounts.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 202: the admin hasn't confirmed the device code on microsoft.com/link yet. */
    private static ResponseEntity<?> pending() {
        return ResponseEntity.accepted().body(Map.of("status", "PENDING"));
    }
}
