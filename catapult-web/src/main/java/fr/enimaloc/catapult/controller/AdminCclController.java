package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.SaveMappingsRequest;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** The admin CCL tab's actions, forwarded to catapult-api with the session's JWT (see {@link AdminMembersController}). */
@RestController
@RequestMapping("/admin/ccl")
@RequiredArgsConstructor
public class AdminCclController {
    private final ApiService apiService;
    private final ModelFiller filler;

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refresh() {
        AdminAccess.require(filler);
        apiService.adminRefreshCcl();
    }

    @PostMapping("/{cclId}/mappings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveMappings(@PathVariable String cclId, @RequestBody SaveMappingsRequest body) {
        AdminAccess.require(filler);
        apiService.adminSaveCclMappings(cclId, body.igdbCategoryIds() == null ? Set.of() : body.igdbCategoryIds());
    }
}
