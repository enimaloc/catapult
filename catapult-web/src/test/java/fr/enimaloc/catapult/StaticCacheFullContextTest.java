package fr.enimaloc.catapult;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The templates' links carry the files' hash, the security chain lets browsers keep what they name, and the responses
 * worth it are compressed.
 */
class StaticCacheFullContextTest extends WebFullContextTestBase {

    @Test
    void thePageNamesVersionedFiles_keptAYear() throws Exception {
        var page = get(port, "/");
        Matcher spa = Pattern.compile("src=\"(/js/spa-[0-9a-f]{32}\\.js)\"").matcher(page.body());
        assertThat(spa.find()).as("versioned spa.js in the page").isTrue();
        assertThat(page.body()).doesNotContain("src=\"/js/spa.js\"");

        var script = get(port, spa.group(1));
        assertThat(script.statusCode()).isEqualTo(200);
        assertThat(script.headers().firstValue("Cache-Control")).hasValue("max-age=31536000, public, immutable");
    }

    @Test
    void anUnversionedFile_isRevalidated() throws Exception {
        assertThat(get(port, "/js/spa.js").headers().firstValue("Cache-Control")).hasValue("no-cache");
    }

    @Test
    void anUnknownVersion_isNeverKept() throws Exception {
        var missing = get(port, "/js/spa-0123456789abcdef0123456789abcdef.js");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test
    void aScript_isCompressed() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/js/spa.js"))
                .header("Accept-Encoding", "gzip").GET().build();
        var script = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(script.headers().firstValue("Content-Encoding")).hasValue("gzip");
    }
}
