package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.common.dto.SiteStatusDto;
import fr.enimaloc.catapult.site.SiteRole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.INTERNAL + "/status/site")
@RequiredArgsConstructor
public class ApiSiteStatusController {

    private final SiteRole siteRole;

    @GetMapping
    public SiteStatusDto status() {
        SiteRole.SiteStatus status = siteRole.status();
        return new SiteStatusDto(status.configured(), status.active(), status.reason(), status.since());
    }
}
