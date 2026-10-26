package fr.enimaloc.catapult.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.FullContextTestBase;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.chatcommand.ChatCommandSetting;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.domain.feedback.FeedbackSubmission;
import fr.enimaloc.catapult.domain.steam.SteamApiKeyEntry;
import fr.enimaloc.catapult.domain.access.AlphaInvite;
import fr.enimaloc.catapult.repository.access.AlphaInviteRepository;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandSettingRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.repository.feedback.FeedbackSubmissionRepository;
import fr.enimaloc.catapult.repository.steam.SteamApiKeyRepository;
import fr.enimaloc.catapult.security.JwtService;
import fr.enimaloc.catapult.service.access.InviteService;
import fr.enimaloc.catapult.service.chatcommand.ChatCommandGate;
import fr.enimaloc.catapult.service.feedback.GitLabClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * On the whole application, the routes whose answer reads a lazy association, or whose write needs a transaction:
 * the read must stay inside the transaction that loaded it, and the write must run in one, wherever the controller's
 * work moves.
 */
@Import(FullContextContainers.class)
class ControllerBoundariesTest extends FullContextTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    UserAccountRepository users;

    @Autowired
    ExperimentRepository experiments;

    @Autowired
    ExperimentAssignmentRepository assignments;

    @Autowired
    JwtService jwtService;

    @Autowired
    InviteService inviteService;

    @Autowired
    ChatCommandSettingRepository chatCommandSettings;

    @Autowired
    SteamApiKeyRepository steamKeys;

    @Autowired
    FeedbackSubmissionRepository feedbackSubmissions;

    @Autowired
    AlphaInviteRepository invites;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** GitLab is a third party: its answer is the issue the submission remembers. */
    @MockitoBean
    GitLabClient gitLab;

    private static ExperimentVariant variant(Experiment experiment, String key, boolean control, int internalId) {
        ExperimentVariant variant = new ExperimentVariant();
        variant.setExperiment(experiment);
        variant.setKey(key);
        variant.setName(key);
        variant.setControl(control);
        variant.setInternalId(internalId);
        return variant;
    }

    private UserAccount member(String name) {
        UserAccount member = new UserAccount();
        member.setTwitchId("boundaries-" + name);
        member.setTwitchUsername(name);
        return users.save(member);
    }

    /** An ACTIVE experiment with a control and a {@code treatment} variant. */
    private Experiment activeExperiment(String key) {
        Experiment experiment = new Experiment();
        experiment.setKey(key);
        experiment.setName(key);
        experiment.setStatus(Experiment.Status.ACTIVE);
        experiment.getVariants().add(variant(experiment, "control", true, 1));
        experiment.getVariants().add(variant(experiment, "treatment", false, 2));
        return experiments.save(experiment);
    }

    private void assign(Experiment experiment, UserAccount member, String variantKey) {
        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setExperiment(experiment);
        assignment.setVariant(experiment.getVariants().stream()
                .filter(v -> v.getKey().equals(variantKey)).findFirst().orElseThrow());
        assignment.setUser(member);
        assignments.save(assignment);
    }

    /** Activates the experiment {@code key} the application created at startup, and rolls {@code member} out to it. */
    private void rollOut(String key, UserAccount member) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Experiment experiment = experiments.findByKey(key).orElseThrow();
            experiment.setStatus(Experiment.Status.ACTIVE);
            ExperimentVariant on = experiment.getVariants().stream().filter(v -> !v.isControl()).findFirst()
                    .orElseGet(() -> {
                        ExperimentVariant added = variant(experiment, "enabled", false,
                                experiment.getVariants().size() + 1);
                        experiment.getVariants().add(added);
                        return added;
                    });
            Experiment saved = experiments.save(experiment);
            assign(saved, member, on.getKey());
        });
    }

    private String jwt(UserAccount member) {
        return jwtService.generateForUser(member, List.of("ROLE_USER"));
    }

    /** {@code method path} with {@code json} (or no body), as {@code member} when not null, else as loopback. */
    private HttpResponse<String> send(String method, String path, UserAccount member, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (member != null) {
            request.header("Authorization", "Bearer " + jwt(member));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void assignedMember_getsTheVariantKey() throws Exception {
        UserAccount member = new UserAccount();
        member.setTwitchId("boundaries-1");
        member.setTwitchUsername("boundaries");
        member = users.save(member);

        Experiment experiment = new Experiment();
        experiment.setKey("boundaries-exp");
        experiment.setName("Boundaries");
        experiment.setStatus(Experiment.Status.ACTIVE);
        experiment.getVariants().add(variant(experiment, "control", true, 1));
        experiment.getVariants().add(variant(experiment, "treatment", false, 2));
        experiment = experiments.save(experiment);
        ExperimentVariant assigned = experiment.getVariants().stream()
                .filter(v -> v.getKey().equals("treatment")).findFirst().orElseThrow();

        ExperimentAssignment assignment = new ExperimentAssignment();
        assignment.setExperiment(experiment);
        assignment.setVariant(assigned);
        assignment.setUser(member);
        assignments.save(assignment);

        String jwt = jwtService.generateForUser(member, List.of("ROLE_USER"));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/internal/users/" + member.getId() + "/experiment-variants/boundaries-exp"))
                .header("Authorization", "Bearer " + jwt)
                .GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"variant\":\"treatment\"}");
    }

    @Test
    void whitelistPage_namesEachInviteOwner() throws Exception {
        UserAccount owner = new UserAccount();
        owner.setTwitchId("boundaries-invite-owner");
        owner.setTwitchUsername("boundaries-inviter");
        owner = users.save(owner);
        inviteService.grantInvite(owner);

        HttpResponse<String> response = get(port, "/api/internal/admin/whitelist");

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> owners = JsonMapper.builder().build().readTree(response.body()).path("invites").valueStream()
                .map(row -> row.path("ownerUsername").asString()).toList();
        assertThat(owners).contains("boundaries-inviter");
    }

    @Test
    void deletingAChatCommandSetting_removesIt() throws Exception {
        UserAccount member = member("setting-deleter");
        rollOut(ChatCommandGate.EXPERIMENT_KEY, member);
        ChatCommandSetting setting = new ChatCommandSetting();
        setting.setUser(member);
        setting.setKey("greeting");
        setting.setValue("hello");
        chatCommandSettings.save(setting);

        HttpResponse<String> response = send("DELETE",
                "/api/internal/users/" + member.getId() + "/chat-command-settings/greeting", member, null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(chatCommandSettings.findByUserAndKey(member, "greeting")).isEmpty();
    }

    @Test
    void resavingAndResharingASteamToken_updatesThePool() throws Exception {
        UserAccount member = member("steam-resaver");
        String path = "/api/internal/users/" + member.getId() + "/settings/steam-personal-token";

        HttpResponse<String> first = send("PUT", path, member, "{\"token\":\"BOUNDARIES-KEY-1\",\"shared\":true}");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(204);

        HttpResponse<String> resave = send("PUT", path, member, "{\"token\":\"BOUNDARIES-KEY-2\",\"shared\":true}");
        assertThat(resave.statusCode()).as(resave.body()).isEqualTo(204);
        assertThat(steamKeys.findByOwner(member)).map(SteamApiKeyEntry::getApiKey).contains("BOUNDARIES-KEY-2");
        assertThat(steamKeys.existsById("BOUNDARIES-KEY-1")).isFalse();

        HttpResponse<String> reshare = send("PUT", path + "/sharing", member, "{\"shared\":false}");
        assertThat(reshare.statusCode()).as(reshare.body()).isEqualTo(204);
        assertThat(steamKeys.findByOwner(member)).hasValueSatisfying(entry -> {
            assertThat(entry.getApiKey()).isEqualTo("BOUNDARIES-KEY-2");
            assertThat(entry.isExclusive()).isTrue();
        });
        assertThat(users.findById(member.getId()).orElseThrow().isSteamTokenShared()).isFalse();
    }

    @Test
    void assigningAMemberToAnExperiment_givesThemTheControlVariant() throws Exception {
        UserAccount member = member("manually-assigned");
        Experiment experiment = activeExperiment("boundaries-assign");

        HttpResponse<String> response = send("POST", "/api/internal/admin/experiments/" + experiment.getId()
                + "/assign", null, "{\"twitchUsername\":\"manually-assigned\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(assignments.findByExperimentAndUser(experiment, member)).isPresent();
    }

    @Test
    void invitesPage_namesEachInviteOwner() throws Exception {
        UserAccount owner = member("invites-page-owner");
        AlphaInvite invite = inviteService.grantInvite(owner);
        HttpResponse<String> response;
        try {
            response = get(port, "/api/internal/admin/invites");
        } finally {
            // whitelistPage_namesEachInviteOwner reads the first invite: leave it alone
            invites.delete(invite);
        }

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<String> owners = JSON.readTree(response.body()).path("invites").valueStream()
                .map(row -> row.path("ownerUsername").asString()).toList();
        assertThat(owners).contains("invites-page-owner");
    }

    @Test
    void feedbackList_answersEachSubmissionWithoutItsMember() throws Exception {
        UserAccount member = member("feedback-lister");
        FeedbackSubmission submission = new FeedbackSubmission();
        submission.setUser(member);
        submission.setType(FeedbackSubmission.Type.BUG);
        submission.setTitle("Crash");
        submission.setDescription("It crashed.");
        submission.setGitlabIssueIid(42);
        submission.setGitlabIssueUrl("https://gitlab/issues/42");
        submission.setLastKnownUpdatedAt(Instant.parse("2026-10-26T10:00:00Z"));
        submission = feedbackSubmissions.save(submission);

        HttpResponse<String> response = send("GET", "/api/internal/users/" + member.getId() + "/feedback",
                member, null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode row = JSON.readTree(response.body()).path(0);
        assertThat(row.propertyNames()).containsExactlyInAnyOrder("id", "type", "title", "description",
                "gitlabIssueIid", "gitlabIssueUrl", "lastKnownUpdatedAt", "subscribed", "createdAt");
        assertThat(row.path("id").asString()).isEqualTo(submission.getId().toString());
        assertThat(row.path("type").asString()).isEqualTo("BUG");
        assertThat(row.path("title").asString()).isEqualTo("Crash");
        assertThat(row.path("gitlabIssueIid").asInt()).isEqualTo(42);
        assertThat(row.path("subscribed").asBoolean()).isTrue();
    }

    @Test
    void feedbackSubmission_answersTheSubmissionWithoutTheCallersAccount() throws Exception {
        UserAccount member = member("feedback-submitter");
        member.setSteamPersonalToken("encrypted-secret");
        member = users.save(member);
        when(gitLab.createIssue(anyString(), anyString(), anyList()))
                .thenReturn(new GitLabClient.CreatedIssue(43, "https://gitlab/issues/43",
                        Instant.parse("2026-10-26T10:00:00Z")));

        HttpResponse<String> response = send("POST", "/api/internal/users/" + member.getId() + "/feedback",
                member, "{\"type\":\"bug\",\"title\":\"Crash\",\"description\":\"It crashed.\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.body()).doesNotContain("steamPersonalToken", "encrypted-secret", "twitchId",
                "boundaries-feedback-submitter", "widgetToken");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.has("user")).isFalse();
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "type", "title", "description",
                "gitlabIssueIid", "gitlabIssueUrl", "lastKnownUpdatedAt", "subscribed", "createdAt");
        assertThat(body.path("title").asString()).isEqualTo("Crash");
        assertThat(body.path("gitlabIssueIid").asInt()).isEqualTo(43);
        assertThat(feedbackSubmissions.findById(UUID.fromString(body.path("id").asString())))
                .isPresent();
    }

    @Test
    void deletionWithAWrongConfirmation_isRefusedAndDeletesNothing() throws Exception {
        UserAccount member = member("careful-streamer");
        String path = "/api/internal/users/" + member.getId() + "/deletion";

        HttpResponse<String> mismatch = send("POST", path, member, "{\"confirmUsername\":\"careless-streamer\"}");

        assertThat(mismatch.statusCode()).as(mismatch.body()).isEqualTo(400);
        assertThat(JSON.readTree(mismatch.body()).path("code").asString()).isEqualTo("bad_request");
        assertThat(users.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(UserAccount.Status.ACTIVE);

        HttpResponse<String> match = send("POST", path, member, "{\"confirmUsername\":\"Careful-Streamer\"}");

        assertThat(match.statusCode()).as(match.body()).isEqualTo(204);
        assertThat(users.findById(member.getId()).orElseThrow().getStatus())
                .isEqualTo(UserAccount.Status.PENDING_DELETION);
    }
}
