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

    @Test
    void aVersionMarker_startsItsSectionWithoutBeingAnEntry() {
        List<ChangelogSection> sections = ChangelogService.parse(List.of(
                "||tag: v1.2.0", "a|feat: x|", "||tag: v1.1.0", "||tag: v1.0.0", "b|fix: y|"));

        assertThat(sections).extracting(ChangelogSection::version).containsExactly("v1.2.0", "v1.0.0");
        assertThat(sections.get(0).entries()).extracting(ChangelogService.ChangelogEntry::hash).containsExactly("a");
    }

    @Test
    void aMergeSubject_isReducedToWhatItMerges() {
        List<ChangelogSection> sections = ChangelogService.parse(List.of(
                "a|fix(api): merge the permanent account deletion fixes into dev|"));

        assertThat(sections.getFirst().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.subject()).isEqualTo("the permanent account deletion fixes");
            assertThat(entry.scope()).isEqualTo("api");
        });
    }

    @Test
    void anOlderNumberedMerge_isKeptAsAnOtherChange() {
        List<ChangelogSection> sections = ChangelogService.parse(List.of("a|merge(#2): fix CI tests suite|"));

        assertThat(sections.getFirst().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo("chore");
            assertThat(entry.scope()).isNull();
            assertThat(entry.subject()).isEqualTo("fix CI tests suite");
        });
    }

    @Test
    void aMergeOfOneBranchIntoAnother_isLeftOut() {
        assertThat(ChangelogService.parse(List.of("a|chore: merge master into feat/web-ui|"))).isEmpty();
    }
}
