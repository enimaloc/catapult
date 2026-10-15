package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.SaveMappingsRequest;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * The admin CCL tab's actions, forwarded to catapult-api with the session's JWT (see {@link AdminMembersController})
 * and answered with its status (502 when it can't be reached).
 */
@RestController
@RequestMapping("/admin/ccl")
@RequiredArgsConstructor
public class AdminCclController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh() {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminRefreshCcl()).build();
    }

    @PostMapping("/{cclId}/mappings")
    public ResponseEntity<Void> saveMappings(@PathVariable String cclId, @RequestBody SaveMappingsRequest body) {
        AdminAccess.require(filler);
        return ResponseEntity.status(apiService.adminSaveCclMappings(
                cclId, body.igdbCategoryIds() == null ? Set.of() : body.igdbCategoryIds())).build();
    }
}
