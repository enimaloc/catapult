package fr.enimaloc.catapult.api.provider;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.service.provider.RawProviderResponseSupport;
import fr.enimaloc.catapult.service.provider.SteamRawQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping(ApiPaths.ADMIN + "/providers/steam")
@RequiredArgsConstructor
public class ApiAdminProviderSteamController {

    private final SteamRawQueries queries;

    @GetMapping("/appdetails")
    public RawProviderResponseSupport.RawProviderResponse appdetails(
            @RequestParam String appId,
            @RequestParam(required = false) String cc,
            @RequestParam(required = false) String l) {
        return queries.appdetails(appId, cc, l);
    }
}
