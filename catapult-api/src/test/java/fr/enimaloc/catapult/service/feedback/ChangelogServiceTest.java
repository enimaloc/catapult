package fr.enimaloc.catapult.service.feedback;

import fr.enimaloc.catapult.service.feedback.ChangelogService.ChangelogEntry;
import fr.enimaloc.catapult.service.feedback.ChangelogService.ChangelogSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChangelogServiceTest {

    @Test
    void eachVersionHeading_startsItsSection() {
        List<ChangelogSection> sections = ChangelogService.parse(List.of(
                "# Changelog", "", "Every published version.", "",
                "## [v1.2.0](https://github.com/o/r/releases/tag/v1.2.0) - 2026-10-30", "",
                "### Features", "- x (abc1234)", "",
                "## v1.1.0", "", "### Fixes", "- y (def5678)"));

        assertThat(sections).extracting(ChangelogSection::version).containsExactly("v1.2.0", "v1.1.0");
        assertThat(sections.get(0).entries()).extracting(ChangelogEntry::subject).containsExactly("x");
    }

    @Test
    void aVersionWithoutEntries_staysListed() {
        List<ChangelogSection> sections = ChangelogService.parse(List.of(
                "## v1.2.0", "", "_Aucune note._", "", "## v1.1.0", "- y", "## v1.0.0"));

        assertThat(sections).extracting(ChangelogSection::version).containsExactly("v1.2.0", "v1.1.0", "v1.0.0");
        assertThat(sections.get(0).entries()).isEmpty();
        assertThat(sections.get(2).entries()).isEmpty();
    }

    @Test
    void aGeneratedEntry_givesItsScopeSubjectAndHash() {
        ChangelogEntry entry = entries("### Fixes", "- **api:** apply the game open (1267d6f)").getFirst();

        assertThat(entry).isEqualTo(new ChangelogEntry("1267d6f", "fix", "api", "apply the game open", false));
    }

    @Test
    void aHandEditedEntry_isKeptAsWritten() {
        ChangelogEntry entry = entries("### Corrections", "- La connexion tient enfin (vraiment)").getFirst();

        assertThat(entry).isEqualTo(new ChangelogEntry(null, "fix", null, "La connexion tient enfin (vraiment)", false));
    }

    @Test
    void aHandWrittenParagraph_isKept() {
        assertThat(entries("### Features", "Une version majeure.", "* une liste", "- [ ] pas une case"))
                .extracting(ChangelogEntry::subject)
                .containsExactly("Une version majeure.", "une liste", "[ ] pas une case");
    }

    @Test
    void eachHeading_givesItsEntriesType() {
        assertThat(entries("### Features", "- a", "### FEATURES", "- b", "### Fixes", "- c",
                "### Refactoring", "- d", "### What's Changed", "- e", "- f"))
                .extracting(ChangelogEntry::type)
                .containsExactly("feat", "feat", "fix", "chore", "chore", "chore");
    }

    @Test
    void anEntryBeforeAnyHeading_isAnOtherChange() {
        assertThat(entries("- a")).extracting(ChangelogEntry::type).containsExactly("chore");
    }

    @Test
    void theFullChangelogLinkAndComments_areLeftOut() {
        assertThat(entries("- a", "**Full Changelog**: https://github.com/o/r/compare/a...b", "<!-- hidden -->"))
                .extracting(ChangelogEntry::subject)
                .containsExactly("a");
    }

    private static List<ChangelogEntry> entries(String... lines) {
        List<String> file = new java.util.ArrayList<>(List.of("## v1.0.0"));
        file.addAll(List.of(lines));
        return ChangelogService.parse(file).getFirst().entries();
    }
}
