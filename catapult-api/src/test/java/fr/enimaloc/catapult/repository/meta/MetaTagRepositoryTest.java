package fr.enimaloc.catapult.repository.meta;

import fr.enimaloc.catapult.domain.meta.MetaTag;
import fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;

import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.NAME;
import static fr.enimaloc.catapult.common.dto.admin.MetaTagAttribute.PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MetaTagRepositoryTest {

    @Autowired MetaTagRepository repository;

    static MetaTag tag(MetaTagAttribute attribute, String key) {
        MetaTag t = new MetaTag();
        t.setAttribute(attribute);
        t.setKey(key);
        t.setContent("x");
        t.setUpdatedAt(Instant.parse("2026-10-19T10:00:00Z"));
        return t;
    }

    @Test
    void findsATagByItsAttributeAndKey_whateverTheKeysCase() {
        repository.save(tag(NAME, "google-site-verification"));
        assertThat(repository.findByAttributeAndKeyIgnoreCase(NAME, "Google-Site-Verification")).isPresent();
        assertThat(repository.findByAttributeAndKeyIgnoreCase(PROPERTY, "google-site-verification")).isEmpty();
    }

    @Test
    void listsTheTagsByAttributeThenKey() {
        repository.save(tag(PROPERTY, "og:image"));
        repository.save(tag(NAME, "theme-color"));
        repository.save(tag(NAME, "author"));
        assertThat(repository.findAllByOrderByAttributeAscKeyAsc()).extracting(MetaTag::getKey)
                .containsExactly("author", "theme-color", "og:image");
    }
}
