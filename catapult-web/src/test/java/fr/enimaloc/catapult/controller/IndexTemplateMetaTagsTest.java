package fr.enimaloc.catapult.controller;

import fr.enimaloc.catapult.common.dto.admin.MetaTagRules;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The keys the admin can't configure are exactly the meta tags index.html writes itself. */
class IndexTemplateMetaTagsTest {
    private static final Pattern META = Pattern.compile("<meta\\b[^>]*?\\b(?:name|property)=\"([^\"]+)\"");

    @Test
    void theReservedKeys_areTheTemplatesOwnMetaTags() throws IOException {
        String html = new ClassPathResource("templates/index.html").getContentAsString(StandardCharsets.UTF_8);
        Set<String> written = META.matcher(html).results().map(m -> m.group(1)).collect(Collectors.toSet());
        assertThat(written).isEqualTo(MetaTagRules.RESERVED);
    }
}
