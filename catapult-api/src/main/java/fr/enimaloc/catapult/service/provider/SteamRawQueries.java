package fr.enimaloc.catapult.service.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** The admin probe of the Steam store: one raw call, answered as the provider sent it. */
@Service
@RequiredArgsConstructor
public class SteamRawQueries {

    private static final String APP_DETAILS_URL = "https://store.steampowered.com/api/appdetails";

    private final RestClient restClient;
    private final RawProviderResponseSupport rawSupport;

    public RawProviderResponseSupport.RawProviderResponse appdetails(String appId, String cc, String l) {
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
