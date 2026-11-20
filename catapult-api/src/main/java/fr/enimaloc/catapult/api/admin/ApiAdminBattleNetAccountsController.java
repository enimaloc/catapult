package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.BgsCallRequest;
import fr.enimaloc.catapult.common.dto.admin.BgsCallResult;
import fr.enimaloc.catapult.common.dto.admin.BgsServiceDto;
import fr.enimaloc.catapult.service.battlenet.BattleNetRawCallService;
import fr.enimaloc.catapult.service.battlenet.BattleNetServiceAccountAdmin;
import fr.enimaloc.catapult.service.battlenet.BattleNetServiceAccountAdmin.AccountDto;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    private final BattleNetRawCallService rawCalls;

    @GetMapping
    public List<AccountDto> list() {
        return accounts.list();
    }

    /** 202: the account is stored and its login runs in the background (poll the list for its auth state). */
    @PostMapping
    public ResponseEntity<AccountDto> create(@RequestBody Map<String, String> body) {
        return ResponseEntity.accepted().body(accounts.create(body.get("email"), body.get("password")));
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

    /** The bgs.desc services and methods the free BGS call can name. */
    @GetMapping("/bgs-methods")
    public List<BgsServiceDto> bgsMethods() {
        return rawCalls.catalog();
    }

    /**
     * An admin's free BGS call on the bot's ready session; 200 even on a non-zero BGS status (it is in the result),
     * 400 for a body that does not parse, 409 without a ready session here or while another call runs on the bot.
     */
    @PostMapping("/{id}/bgs-call")
    public BgsCallResult bgsCall(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @RequestBody BgsCallRequest body) {
        return rawCalls.call(id, body, jwt == null ? null : jwt.getSubject());
    }
}
