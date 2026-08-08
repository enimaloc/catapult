package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@Slf4j
@RestController
@RequestMapping("/api/admin/providers/steam")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminProviderSteamController {

    private static final String APP_DETAILS_URL = "https://store.steampowered.com/api/appdetails";

    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    @GetMapping("/appdetails")
    public RawProviderResponseSupport.RawProviderResponse appdetails(
            @RequestParam String appId,
            @RequestParam(required = false) String cc,
            @RequestParam(required = false) String l) {
        StringBuilder uri = new StringBuilder(APP_DETAILS_URL + "?appids=" + appId);
        if (cc != null && !cc.isBlank()) {
            uri.append("&cc=").append(cc);
        }
        if (l != null && !l.isBlank()) {
            uri.append("&l=").append(l);
        }
        return rawSupport.fetch(() -> restClient.get()
                .uri(uri.toString())
                .retrieve()
                .body(String.class));
    }
}
