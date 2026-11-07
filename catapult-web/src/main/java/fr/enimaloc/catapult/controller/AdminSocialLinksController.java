package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.SocialLinkOrderRequest;
import fr.enimaloc.catapult.common.dto.admin.SocialLinkRequest;
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
 * The admin Links tab's social link actions, forwarded to catapult-api with the session's JWT and answered
 * with its status (502 when unreachable); a change shows on the next page (the pages' copy is emptied).
 */
@RestController
@RequestMapping("/admin/social-links")
@RequiredArgsConstructor
public class AdminSocialLinksController {
    private final ApiService apiService;
    private final ModelFiller filler;
    private final SiteSocialLinks siteSocialLinks;

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody SocialLinkRequest body) {
        return relay(() -> apiService.adminCreateSocialLink(body));
    }

    @PostMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody SocialLinkRequest body) {
        return relay(() -> apiService.adminUpdateSocialLink(id, body));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        return relay(() -> apiService.adminDeleteSocialLink(id));
    }

    /** Every link id in the order the links must be shown. */
    @PostMapping("/order")
    public ResponseEntity<Void> order(@RequestBody SocialLinkOrderRequest body) {
        return relay(() -> apiService.adminOrderSocialLinks(body.ids()));
    }

    private ResponseEntity<Void> relay(IntSupplier action) {
        return AdminAccess.relay(filler, action, siteSocialLinks::clear);
    }
}
