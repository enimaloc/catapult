package fr.enimaloc.catapult.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalModelAdviceTest {

    @Test
    void withBuildProperties_populatesModelAttributes() {
        Properties p = new Properties();
        p.setProperty("version", "1.0.0");
        p.setProperty("git.branch", "main");
        p.setProperty("git.commit", "abc1234");
        p.setProperty("git.github-url", "https://github.com/enimaloc/catapult");
        BuildProperties buildProperties = new BuildProperties(p);

        GlobalModelAdvice advice = new GlobalModelAdvice(Optional.of(buildProperties));
        ExtendedModelMap model = new ExtendedModelMap();
        advice.addBuildInfo(model);

        assertThat(model.getAttribute("appVersion")).isEqualTo("1.0.0");
        GlobalModelAdvice.GitData git = (GlobalModelAdvice.GitData) model.getAttribute("git");
        assertThat(git).isNotNull();
        assertThat(git.branch()).isEqualTo("main");
        assertThat(git.commit()).isEqualTo("abc1234");
        assertThat(git.repositoryUrl()).isEqualTo("https://github.com/enimaloc/catapult");
    }

    @Test
    void withBuildPropertiesNonHttpsGithubUrl_usesFallback() {
        Properties p = new Properties();
        p.setProperty("git.github-url", "javascript:alert(1)");
        BuildProperties buildProperties = new BuildProperties(p);

        GlobalModelAdvice advice = new GlobalModelAdvice(Optional.of(buildProperties));
        ExtendedModelMap model = new ExtendedModelMap();
        advice.addBuildInfo(model);

        GlobalModelAdvice.GitData git = (GlobalModelAdvice.GitData) model.getAttribute("git");
        assertThat(git).isNotNull();
        assertThat(git.repositoryUrl()).isNull();
    }

    @Test
    void withBuildPropertiesMissingGitProperties_usesFallbacks() {
        Properties p = new Properties();
        p.setProperty("version", "1.0.0");
        BuildProperties buildProperties = new BuildProperties(p);

        GlobalModelAdvice advice = new GlobalModelAdvice(Optional.of(buildProperties));
        ExtendedModelMap model = new ExtendedModelMap();
        advice.addBuildInfo(model);

        assertThat(model.getAttribute("appVersion")).isEqualTo("1.0.0");
        GlobalModelAdvice.GitData git = (GlobalModelAdvice.GitData) model.getAttribute("git");
        assertThat(git).isNotNull();
        assertThat(git.branch()).isEqualTo("unknown");
        assertThat(git.commit()).isEqualTo("unknown");
        assertThat(git.repositoryUrl()).isNull();
    }

    @Test
    void withoutBuildProperties_usesFallbacks() {
        GlobalModelAdvice advice = new GlobalModelAdvice(Optional.empty());
        ExtendedModelMap model = new ExtendedModelMap();
        advice.addBuildInfo(model);

        assertThat(model.getAttribute("appVersion")).isEqualTo("dev");
        GlobalModelAdvice.GitData git = (GlobalModelAdvice.GitData) model.getAttribute("git");
        assertThat(git).isNotNull();
        assertThat(git.branch()).isEqualTo("unknown");
        assertThat(git.commit()).isEqualTo("unknown");
        assertThat(git.repositoryUrl()).isNull();
    }

    @Test
    void commonAttribute_populatesAppWithNestedStructure() {
        GlobalModelAdvice advice = new GlobalModelAdvice(Optional.empty());
        ReflectionTestUtils.setField(advice, "accountDeletionDelayDays", 7);
        ReflectionTestUtils.setField(advice, "appName", "catapult");
        ReflectionTestUtils.setField(advice, "defaultNoGameName", "Just Chatting");
        ReflectionTestUtils.setField(advice, "defaultNoGameId", "509658");

        ExtendedModelMap model = new ExtendedModelMap();
        advice.commonAttribute(null, model);

        GlobalModelAdvice.App app = (GlobalModelAdvice.App) model.getAttribute("app");
        assertThat(app).isNotNull();
        assertThat(app.name()).isEqualTo("catapult");
        assertThat(app.account().deletionDelayDays()).isEqualTo(7);
        assertThat(app.twitch().defaultNoGameName()).isEqualTo("Just Chatting");
        assertThat(app.twitch().defaultNoGameId()).isEqualTo("509658");
    }

    @Test
    void commonAttribute_appHasDifferentDeletionDelayAndName() {
        GlobalModelAdvice advice = new GlobalModelAdvice(Optional.empty());
        ReflectionTestUtils.setField(advice, "accountDeletionDelayDays", 14);
        ReflectionTestUtils.setField(advice, "appName", "myapp");
        ReflectionTestUtils.setField(advice, "defaultNoGameName", "Just Chatting");
        ReflectionTestUtils.setField(advice, "defaultNoGameId", "509658");

        ExtendedModelMap model = new ExtendedModelMap();
        advice.commonAttribute(null, model);

        GlobalModelAdvice.App app = (GlobalModelAdvice.App) model.getAttribute("app");
        assertThat(app.account().deletionDelayDays()).isEqualTo(14);
        assertThat(app.name()).isEqualTo("myapp");
    }
}
