package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.service.metrics.ExternalApiObservations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class XboxStoreServiceImplTest {

    private static final String CATALOG = "https://displaycatalog.mp.microsoft.com/v7.0/products/";

    private MockRestServiceServer catalog;
    private XboxStoreServiceImpl service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        catalog = MockRestServiceServer.bindTo(builder).build();
        service = new XboxStoreServiceImpl(builder.build(),
                new ExternalApiObservations(ObservationRegistry.create(), new SimpleMeterRegistry()));
    }

    private void respond(String url, String json) {
        catalog.expect(requestTo(url)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private static final String HALO = """
            {"Product": {
              "LastModifiedDate": "2024-05-01T10:20:30.1234567Z",
              "LocalizedProperties": [{
                "ProductTitle": "Halo Infinite", "ShortDescription": "Master Chief returns.",
                "PublisherName": "Xbox Game Studios", "DeveloperName": "343 Industries",
                "SupportUri": "https://support",
                "Images": [{"Uri": "//store-images/cover.png", "ImagePurpose": "Poster", "Width": 720, "Height": 1080},
                           {"Uri": "https://store-images/hero.png", "ImagePurpose": "SuperHeroArt", "Width": 1920, "Height": 1080}],
                "CMSVideos": [{"DASH": "https://dash", "HLS": "https://hls", "Caption": "Trailer", "Width": 1920, "Height": 1080,
                               "PreviewImage": {"Uri": "//preview.png", "Width": 1, "Height": 1}}],
                "Franchises": ["Halo"],
                "EligibilityProperties": {"Affirmations": [{"Description": "Included with Game Pass"}, {"Description": " "}]}
              }]
            }}""";

    @Test
    void mapsTheProductAndNormalizesUris() {
        respond(CATALOG + "9PP5G1F0C2B6?market=FR&languages=fr-FR&fieldsTemplate=Details", HALO);

        XboxStoreService.XboxProduct product = service.fetchProduct("9PP5G1F0C2B6", Locale.FRANCE).orElseThrow();

        assertThat(product.title()).isEqualTo("Halo Infinite");
        assertThat(product.description()).isEqualTo("Master Chief returns.");
        assertThat(product.developerName()).isEqualTo("343 Industries");
        assertThat(product.supportUri()).isEqualTo("https://support");
        assertThat(product.lastModifiedDate()).isEqualTo(Instant.parse("2024-05-01T10:20:30.1234567Z"));
        assertThat(product.images()).extracting(XboxStoreService.XboxImage::uri)
                .containsExactly("https://store-images/cover.png", "https://store-images/hero.png");
        assertThat(product.videos()).singleElement().satisfies(video -> {
            assertThat(video.caption()).isEqualTo("Trailer");
            assertThat(video.previewImageUri()).isEqualTo("https://preview.png");
        });
        assertThat(product.franchises()).containsExactly("Halo");
        assertThat(product.gamePassAffirmations()).containsExactly("Included with Game Pass");
        assertThat(product.storeUrl()).isEqualTo("https://www.microsoft.com/store/apps/9PP5G1F0C2B6");
        catalog.verify();
    }

    @Test
    void languageWithoutCountry_queriesTheUsMarket() {
        respond(CATALOG + "P?market=US&languages=fr&fieldsTemplate=Details", HALO);

        assertThat(service.fetchProduct("P", Locale.FRENCH)).isPresent();
    }

    @Test
    void noLocale_meansUsEnglish() {
        respond(CATALOG + "P?market=US&languages=en-US&fieldsTemplate=Details", HALO);
        assertThat(service.fetchProduct("P", null)).isPresent();
    }

    @Test
    void undeterminedLocale_meansUsEnglish() {
        respond(CATALOG + "P?market=US&languages=en-US&fieldsTemplate=Details", HALO);
        assertThat(service.fetchProduct("P", Locale.ROOT)).isPresent();
    }

    @Test
    void missingOptionalBlocks_becomeEmpty() {
        respond(CATALOG + "P?market=US&languages=en-US&fieldsTemplate=Details", """
                {"Product": {"LastModifiedDate": "not a date",
                 "LocalizedProperties": [{"ProductTitle": "Bare",
                   "CMSVideos": [{"Width": 1, "Height": 1}],
                   "EligibilityProperties": {}}]}}""");

        XboxStoreService.XboxProduct product = service.fetchProduct("P", Locale.US).orElseThrow();

        assertThat(product.lastModifiedDate()).isNull();
        assertThat(product.images()).isEmpty();
        assertThat(product.videos()).singleElement().satisfies(video -> assertThat(video.previewImageUri()).isNull());
        assertThat(product.franchises()).isEmpty();
        assertThat(product.gamePassAffirmations()).isEmpty();
    }

    @Test
    void noProductOrLocalizedProperties_isEmpty() {
        respond(CATALOG + "A?market=US&languages=en-US&fieldsTemplate=Details", "{}");
        respond(CATALOG + "B?market=US&languages=en-US&fieldsTemplate=Details", "{\"Product\": {\"LocalizedProperties\": []}}");
        respond(CATALOG + "C?market=US&languages=en-US&fieldsTemplate=Details", "{\"Product\": {}}");

        assertThat(service.fetchProduct("A", Locale.US)).isEmpty();
        assertThat(service.fetchProduct("B", Locale.US)).isEmpty();
        assertThat(service.fetchProduct("C", Locale.US)).isEmpty();
    }

    @Test
    void catalogErrors_areEmpty() {
        catalog.expect(requestTo(CATALOG + "P?market=US&languages=en-US&fieldsTemplate=Details"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(service.fetchProduct("P", Locale.US)).isEmpty();
    }
}
