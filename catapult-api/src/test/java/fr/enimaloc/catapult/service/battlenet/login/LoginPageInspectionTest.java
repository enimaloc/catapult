package fr.enimaloc.catapult.service.battlenet.login;

import fr.enimaloc.catapult.service.battlenet.login.LoginPageInspection.PageElement;
import fr.enimaloc.catapult.service.battlenet.login.LoginPageInspection.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginPageInspectionTest {

    private static final String BASE = "https://eu.account.battle.net";
    private static final String UUID = "3f2b8c1e-9a4d-4e7b-8c21-0d5f6a7b8c9d";

    @Test
    void classifiesTheSecurityCheckChoosePage() {
        assertThat(LoginPageInspection.classify(BASE + "/login/en-us/challenge/" + UUID + "/choose"))
                .isEqualTo(Step.CHALLENGE_CHOOSE);
    }

    @Test
    void classifiesTheCodeEntryPage() {
        assertThat(LoginPageInspection.classify(BASE + "/login/fr-fr/challenge/" + UUID))
                .isEqualTo(Step.CHALLENGE_CODE);
    }

    @Test
    void classifiesOtherLoginPagesAsOther() {
        assertThat(LoginPageInspection.classify(BASE + "/login/en-us/password")).isEqualTo(Step.OTHER);
        assertThat(LoginPageInspection.classify(BASE + "/login/?externalChallenge=login&app=app"))
                .isEqualTo(Step.OTHER);
        assertThat(LoginPageInspection.classify("http://localhost:0/?ST=EU-abc-1")).isEqualTo(Step.OTHER);
    }

    @Test
    void ignoresQueryStringsAndFragmentsWhenClassifying() {
        assertThat(LoginPageInspection.classify(BASE + "/login/en-us/challenge/" + UUID + "/choose?a=/password"))
                .isEqualTo(Step.CHALLENGE_CHOOSE);
        assertThat(LoginPageInspection.classify(BASE + "/login/fr-fr/challenge/" + UUID + "?x=1#top"))
                .isEqualTo(Step.CHALLENGE_CODE);
        assertThat(LoginPageInspection.classify(BASE + "/login/en-us/password?next=/challenge/" + UUID))
                .isEqualTo(Step.OTHER);
    }

    @Test
    void describeStripsTheQueryString() {
        String line = LoginPageInspection.describe("http://localhost:0/?ST=EU-secret-1", "t", List.of());
        assertThat(line).contains("url=http://localhost:0/").doesNotContain("ST=").doesNotContain("secret");
    }

    @Test
    void describeListsControlsWithoutInputValues() {
        List<PageElement> elements = List.of(
                new PageElement("input", null, "inputs[0].value", "text", null, "123456"),
                new PageElement("select", "method", "method", null, null, "E-Mail"),
                new PageElement("textarea", "notes", null, null, null, "hunter2"),
                new PageElement("button", "submit", null, "submit", null, "Submit"),
                new PageElement("div", "display-errors", null, null, "alert", "Something went wrong"));

        String line = LoginPageInspection.describe(BASE + "/login/en-us/challenge/" + UUID, "Security Check",
                elements);

        assertThat(line)
                .contains("title=\"Security Check\"")
                .contains("input[name=inputs[0].value][type=text]")
                .contains("select#method[name=method]")
                .contains("button#submit[type=submit] \"Submit\"")
                .contains("div#display-errors[role=alert] \"Something went wrong\"")
                .doesNotContain("123456")
                .doesNotContain("E-Mail")
                .doesNotContain("hunter2");
    }

    @Test
    void describeTruncatesLongTexts() {
        String longText = "x".repeat(200);
        String line = LoginPageInspection.describe(BASE + "/login/en-us/password", longText,
                List.of(new PageElement("div", null, null, null, "alert", longText)));

        assertThat(line).contains("x".repeat(LoginPageInspection.MAX_TEXT) + "...")
                .doesNotContain("x".repeat(LoginPageInspection.MAX_TEXT + 1));
    }

    @Test
    void describeMasksEmailsInTexts() {
        String line = LoginPageInspection.describe(BASE + "/login/en-us/password", "Login",
                List.of(new PageElement("div", null, null, null, "alert", "No account for bot@example.com here")));

        assertThat(line).doesNotContain("bot@example.com").contains("No account for *** here");
    }
}
