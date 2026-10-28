package fr.enimaloc.catapult.service.feedback;

import fr.enimaloc.catapult.service.feedback.ChangelogService.ChangelogSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChangelogServiceTest {

    @Test
    void commitsAfterTheLastTag_haveNoVersion() {
        List<ChangelogSection> sections = ChangelogService.parse(List.of(
                "a|feat: x|HEAD -> master", "b|fix: y|tag: v1.2.0", "c|feat: z|"));

        assertThat(sections).extracting(ChangelogSection::version).containsExactly(null, "v1.2.0");
        assertThat(sections.get(0).entries()).extracting(ChangelogService.ChangelogEntry::hash).containsExactly("a");
        assertThat(sections.get(1).entries()).extracting(ChangelogService.ChangelogEntry::hash).containsExactly("b", "c");
    }

    @Test
    void aTagOnTheNewestCommit_leavesNoUnversionedSection() {
        List<ChangelogSection> sections = ChangelogService.parse(List.of("b|fix: y|tag: v1.2.0"));

        assertThat(sections).extracting(ChangelogSection::version).containsExactly("v1.2.0");
    }

    @Test
    void noTag_givesOneUnversionedSection() {
        List<ChangelogSection> sections = ChangelogService.parse(List.of("a|feat: x|"));

        assertThat(sections).extracting(ChangelogSection::version).containsExactly((String) null);
    }
}
