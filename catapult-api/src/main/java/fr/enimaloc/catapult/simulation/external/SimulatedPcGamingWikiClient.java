package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiClient;
import fr.enimaloc.catapult.service.enrichment.pcgw.PcGamingWikiPage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** PCGamingWiki in the mock profile: every game has a page with ultrawide and controller support, nothing leaves. */
@Primary
@Component
@ConditionalOnBooleanProperty(name = "catapult.simulation.enabled")
public class SimulatedPcGamingWikiClient implements PcGamingWikiClient {

    private static final long PAGE_ID = 1L;

    @Override
    public Optional<PcGamingWikiPage> findBySteamAppId(String appId) {
        return Optional.of(page("Simulated game " + appId));
    }

    @Override
    public Optional<PcGamingWikiPage> findByName(String name) {
        return Optional.of(page(name));
    }

    @Override
    public Optional<PcGamingWikiPage> findByPageId(long pageId) {
        return Optional.of(page("Simulated game " + pageId));
    }

    private static PcGamingWikiPage page(String title) {
        String url = "https://www.pcgamingwiki.com/wiki/"
                + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8);
        return new PcGamingWikiPage(PAGE_ID, title, url, "true", "true", "limited", List.of());
    }
}
