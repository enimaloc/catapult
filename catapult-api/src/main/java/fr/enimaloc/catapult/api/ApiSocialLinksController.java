package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.admin.SocialLinkDto;
import fr.enimaloc.catapult.service.social.SocialLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The visible social links, read by catapult-web for its footer (public, as the footer shows them). */
@RestController
@RequiredArgsConstructor
public class ApiSocialLinksController {
    private final SocialLinkService service;

    @GetMapping(ApiPaths.INTERNAL + "/social-links")
    public List<SocialLinkDto> list() {
        return service.listVisible();
    }
}
