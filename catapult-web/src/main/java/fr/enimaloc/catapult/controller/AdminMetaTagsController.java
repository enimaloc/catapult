package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MetaTagRequest;
import fr.enimaloc.catapult.service.ApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.IntSupplier;

/**
 * The admin Meta tab's meta tag actions, forwarded to catapult-api with the session's JWT and answered
 * with its status (502 when unreachable); a change shows on the next page (the pages' copy is emptied).
 */
@RestController
@RequestMapping("/admin/meta/tags")
@RequiredArgsConstructor
public class AdminMetaTagsController {
    private final ApiService apiService;
    private final ModelFiller filler;
    private final SiteMetaTags siteMetaTags;

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody MetaTagRequest body) {
        return relay(() -> apiService.adminCreateMetaTag(body));
    }

    @PostMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody MetaTagRequest body) {
        return relay(() -> apiService.adminUpdateMetaTag(id, body));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        return relay(() -> apiService.adminDeleteMetaTag(id));
    }

    private ResponseEntity<Void> relay(IntSupplier action) {
        return AdminAccess.relay(filler, action, siteMetaTags::clear);
    }
}
