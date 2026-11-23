package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantDto;
import fr.enimaloc.catapult.common.dto.admin.BattleNetGameVariantRequest;
import fr.enimaloc.catapult.service.battlenet.BattleNetGameVariantAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The admin Battle.net game variants: rules turning a game into a variant of its own (e.g. "WoW:Forever") when the
 * friend's activity text matches. 201 created, 204 deleted; 400 invalid, 404 unknown, 409 key already used.
 */
@RestController
@RequestMapping(ApiPaths.ADMIN + "/battlenet-game-variants")
@RequiredArgsConstructor
public class ApiAdminBattleNetGameVariantsController {

    private final BattleNetGameVariantAdmin variants;
    private final ApiUserResolver userResolver;

    @GetMapping
    public List<BattleNetGameVariantDto> list() {
        return variants.list();
    }

    @PostMapping
    public ResponseEntity<BattleNetGameVariantDto> create(
            @RequestBody(required = false) BattleNetGameVariantRequest body, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(variants.create(body, userResolver.viewer(jwt)));
    }

    @PutMapping("/{id}")
    public BattleNetGameVariantDto update(@PathVariable UUID id,
                                          @RequestBody(required = false) BattleNetGameVariantRequest body,
                                          @AuthenticationPrincipal Jwt jwt) {
        return variants.update(id, body, userResolver.viewer(jwt));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        variants.delete(id);
        return ResponseEntity.noContent().build();
    }
}
