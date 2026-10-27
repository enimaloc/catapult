package fr.enimaloc.catapult.repository.social;

import fr.enimaloc.catapult.common.dto.admin.SocialPlatform;
import fr.enimaloc.catapult.domain.social.SocialLink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:socialdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE,POSITION",
        "logging.level.org.hibernate.tool.schema=ERROR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SocialLinkRepositoryTest {

    @Autowired SocialLinkRepository repository;

    SocialLink save(String url, boolean visible, int position) {
        SocialLink l = new SocialLink();
        l.setPlatform(SocialPlatform.OTHER);
        l.setUrl(url);
        l.setVisible(visible);
        l.setPosition(position);
        return repository.save(l);
    }

    @Test
    void listsByPosition_theVisibleOnesForThePublic() {
        save("https://c", true, 2);
        save("https://a", true, 0);
        save("https://b", false, 1);
        assertThat(repository.findAllByOrderByPositionAscIdAsc()).extracting(SocialLink::getUrl)
                .containsExactly("https://a", "https://b", "https://c");
        assertThat(repository.findByVisibleTrueOrderByPositionAscIdAsc()).extracting(SocialLink::getUrl)
                .containsExactly("https://a", "https://c");
        assertThat(repository.findFirstByOrderByPositionDesc()).get().extracting(SocialLink::getUrl)
                .isEqualTo("https://c");
    }
}
