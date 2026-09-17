package fr.enimaloc.catapult.service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mock.xbox-store", havingValue = "false", matchIfMissing = true)
public class XboxStoreServiceImpl implements XboxStoreService {

    // Unofficial/undocumented Microsoft Store catalog API (community-reverse-engineered, same
    // trust level as DTDD's — see RealDtddApiClient). Called without auth: store listings are
    // public catalog data, unlike XboxService/XboxGameGetter's XSTS-authenticated presence calls.
    private static final String DISPLAY_CATALOG_URL = "https://displaycatalog.mp.microsoft.com/v7.0/products/";

    private final RestClient restClient;
    private final ExternalApiObservations apiObservations;

    @Override
    public Optional<XboxProduct> fetchProduct(String productId, Locale locale) {
        return apiObservations.observe("xbox_store", "fetch_product", () -> {
            try {
                String languageTag = locale == null || "und".equals(locale.toLanguageTag())
                        ? "en-US" : locale.toLanguageTag();
                String market = locale != null && !locale.getCountry().isBlank() ? locale.getCountry() : "US";
                DisplayCatalogResponse response = restClient.get()
                        .uri(DISPLAY_CATALOG_URL + productId + "?market=" + market
                                + "&languages=" + languageTag + "&fieldsTemplate=Details")
                        .retrieve()
                        .body(DisplayCatalogResponse.class);
                if (response == null || response.product() == null) {
                    return Optional.empty();
                }
                List<LocalizedProperties> localized = response.product().localizedProperties();
                if (localized == null || localized.isEmpty()) {
                    return Optional.empty();
                }
                LocalizedProperties props = localized.get(0);
                return Optional.of(new XboxProduct(
                        props.productTitle(),
                        props.shortDescription(),
                        props.publisherName(),
                        props.developerName(),
                        props.publisherAddress(),
                        props.publisherWebsiteUri(),
                        props.supportUri(),
                        props.supportPhone(),
                        parseInstant(response.product().lastModifiedDate()),
                        images(props.images()),
                        videos(props.cmsVideos()),
                        franchises(props.franchises()),
                        gamePassAffirmations(props.eligibilityProperties()),
                        "https://www.microsoft.com/store/apps/" + productId));
            } catch (Exception e) {
                log.warn("Xbox store fetchProduct failed for productId={}: {}", productId, e.getMessage());
                return Optional.empty();
            }
        });
    }

    // Display Catalog image URIs are protocol-relative ("//store-images...") — the store page
    // itself resolves them against its own scheme, but a bare URI is invalid outside a browser.
    private static String normalizeUri(String uri) {
        return uri != null && uri.startsWith("//") ? "https:" + uri : uri;
    }

    private static List<XboxImage> images(List<Image> images) {
        if (images == null) return List.of();
        return images.stream()
                .map(i -> new XboxImage(normalizeUri(i.uri()), i.imagePurpose(), i.width(), i.height(), i.caption()))
                .toList();
    }

    private static List<XboxVideo> videos(List<Video> videos) {
        if (videos == null) return List.of();
        return videos.stream()
                .map(v -> new XboxVideo(v.caption(), v.dash(), v.hls(),
                        v.previewImage() != null ? normalizeUri(v.previewImage().uri()) : null,
                        v.width(), v.height()))
                .toList();
    }

    private static List<String> franchises(List<String> franchises) {
        return franchises == null ? List.of() : franchises;
    }

    private static List<String> gamePassAffirmations(EligibilityProperties eligibility) {
        if (eligibility == null || eligibility.affirmations() == null) return List.of();
        return eligibility.affirmations().stream().map(Affirmation::description)
                .filter(d -> d != null && !d.isBlank())
                .toList();
    }

    // "LastModifiedDate" is .NET-style ISO-8601 with sub-second precision (e.g. 7 fractional
    // digits) — Instant.parse handles it, but a schema surprise here shouldn't sink the whole
    // product fetch, so parse defensively rather than let the caller's catch-all swallow it.
    private static Instant parseInstant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisplayCatalogResponse(@JsonAlias("Product") Product product) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Product(@JsonAlias("LastModifiedDate") String lastModifiedDate,
                          @JsonAlias("LocalizedProperties") List<LocalizedProperties> localizedProperties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocalizedProperties(
            @JsonAlias("ProductTitle") String productTitle,
            @JsonAlias("ShortDescription") String shortDescription,
            @JsonAlias("PublisherName") String publisherName,
            @JsonAlias("DeveloperName") String developerName,
            @JsonAlias("PublisherAddress") String publisherAddress,
            @JsonAlias("PublisherWebsiteUri") String publisherWebsiteUri,
            @JsonAlias("SupportUri") String supportUri,
            @JsonAlias("SupportPhone") String supportPhone,
            @JsonAlias("Images") List<Image> images,
            @JsonAlias("CMSVideos") List<Video> cmsVideos,
            @JsonAlias("Franchises") List<String> franchises,
            @JsonAlias("EligibilityProperties") EligibilityProperties eligibilityProperties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(@JsonAlias("Uri") String uri, @JsonAlias("ImagePurpose") String imagePurpose,
                        @JsonAlias("Width") int width, @JsonAlias("Height") int height,
                        @JsonAlias("Caption") String caption) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Video(@JsonAlias("DASH") String dash, @JsonAlias("HLS") String hls,
                        @JsonAlias("Caption") String caption, @JsonAlias("Width") int width,
                        @JsonAlias("Height") int height, @JsonAlias("PreviewImage") Image previewImage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EligibilityProperties(@JsonAlias("Affirmations") List<Affirmation> affirmations) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Affirmation(@JsonAlias("Description") String description) {}
}
