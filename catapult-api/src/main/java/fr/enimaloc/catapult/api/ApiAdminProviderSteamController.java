package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.api.provider.RawProviderResponseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@Slf4j
@RestController
@RequestMapping("/api/admin/providers/steam")
@RequiredArgsConstructor
public class ApiAdminProviderSteamController {

    private static final String APP_DETAILS_URL = "https://store.steampowered.com/api/appdetails";

    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    @GetMapping("/appdetails")
    public RawProviderResponseSupport.RawProviderResponse appdetails(@RequestParam String appId) {
        return rawSupport.fetch(() -> restClient.get()
                .uri(APP_DETAILS_URL + "?appids=" + appId)
                .retrieve()
                .body(String.class));
    }
}
