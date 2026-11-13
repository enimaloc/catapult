package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.service.battlenet.BattleNetServiceAccountAdmin;
import fr.enimaloc.catapult.service.battlenet.BattleNetServiceAccountAdmin.AccountDto;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/battlenet-accounts")
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("battlenet.enabled")
public class ApiAdminBattleNetAccountsController {

    private final BattleNetServiceAccountAdmin accounts;

    @GetMapping
    public List<AccountDto> list() {
        return accounts.list();
    }

    /** 202: the account is stored and its login runs in the background (poll the list for its auth state). */
    @PostMapping
    public ResponseEntity<AccountDto> create(@RequestBody Map<String, String> body) {
        return ResponseEntity.accepted().body(
                accounts.create(body.get("label"), body.get("email"), body.get("password")));
    }

    /** The 2FA code of a login waiting for one; 409 when none is. */
    @PostMapping("/{id}/code")
    public ResponseEntity<Void> submitCode(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        accounts.submitCode(id, body.get("code"));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/reauth")
    public ResponseEntity<AccountDto> reauth(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(accounts.reauth(id));
    }

    @PatchMapping("/{id}")
    public AccountDto update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return accounts.update(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        accounts.delete(id);
        return ResponseEntity.noContent().build();
    }
}
