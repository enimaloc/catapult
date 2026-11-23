package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantDto;
import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantRequest;
import fr.enimaloc.catapult.service.ApiService;
import fr.enimaloc.catapult.service.http.ApiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The Battle.net game variants section's actions (create, change and delete a variant) in the admin game mappings
 * tab, forwarded to catapult-api with the session's JWT. They answer catapult-api's status (201 created, 200, 204
 * deleted; 400 invalid; 404 unknown; 409 key already used) and its variant as JSON; 502 when it is unreachable.
 */
@RestController
@RequestMapping("/admin/battlenet-game-variants")
@RequiredArgsConstructor
public class AdminBattleNetGameVariantsController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping
    public ResponseEntity<BattleNetGameVariantDto> create(@RequestBody BattleNetGameVariantRequest body) {
        AdminAccess.require(filler);
        return answer(apiService.adminCreateBattleNetGameVariant(body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BattleNetGameVariantDto> update(@PathVariable UUID id,
                                                          @RequestBody BattleNetGameVariantRequest body) {
        AdminAccess.require(filler);
        return answer(apiService.adminUpdateBattleNetGameVariant(id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        AdminAccess.require(filler);
        return answer(apiService.adminDeleteBattleNetGameVariant(id));
    }

    private static <T> ResponseEntity<T> answer(ApiClient.Result<T> result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
