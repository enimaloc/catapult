package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.GameGetter;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final Optional<BuildProperties> buildProperties;
    private final List<GameGetter> availableGetters;

    @Value("${app.account.deletion-delay-days}")
    private int accountDeletionDelayDays;

    @Value("${spring.application.name}")
    private String appName;

    @Value("${twitch.default-no-game.name}")
    private String defaultNoGameName;

    @Value("${twitch.default-no-game.id}")
    private String defaultNoGameId;

    @ModelAttribute
    public void addBuildInfo(Model model) {
        model.addAttribute("appVersion", buildProperties.map(BuildProperties::getVersion).orElse("dev"));
        model.addAttribute("git", new GitData(
                buildProperties.map(p -> p.get("git.branch")).orElse("unknown"),
                buildProperties.map(p -> p.get("git.commit")).orElse("unknown"),
                buildProperties.map(p -> p.get("git.repository-url"))
                        .filter(url -> url.startsWith("https://"))
                        .orElse(null)
        ));
    }

    @ModelAttribute
    public void commonAttribute(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        UserAccount user = principal != null ? principal.getUserAccount() : null;

        model.addAttribute("user", user);
        model.addAttribute("isPendingDeletion", user != null && user.getStatus() == UserAccount.Status.PENDING_DELETION);

        model.addAttribute("app", new App(
                appName,
                new App.Account(accountDeletionDelayDays),
                new App.Twitch(defaultNoGameName, defaultNoGameId),
                availableGetters.stream().map(getter -> new App.Getters(getter.name())).toArray(App.Getters[]::new)
        ));
    }

    public record GitData(String branch, String commit, String repositoryUrl) {}

    public record App(String name, Account account, Twitch twitch, Getters[] getters, String[] gettersName) {
        public App(String name, Account account, Twitch twitch, Getters[] getters) {
            this(name, account, twitch, getters, Arrays.stream(getters).map(Getters::name).toArray(String[]::new));
        }

        public record Account(int deletionDelayDays) {}
        public record Twitch(String defaultNoGameName, String defaultNoGameId) {}
        public record Getters(String name) {}
    }

}
