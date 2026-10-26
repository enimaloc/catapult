package fr.enimaloc.catapult.api;

import static org.assertj.core.api.Assertions.assertThat;

import fr.enimaloc.catapult.FullContextContainers;
import fr.enimaloc.catapult.FullContextTestBase;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentAssignment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.security.JwtService;
import fr.enimaloc.catapult.service.access.InviteService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * On the whole application, the routes whose answer reads a lazy association: the read must stay inside the
 * transaction that loaded it, wherever the controller's work moves.
 */
@Import(FullContextContainers.class)
class ControllerBoundariesTest extends FullContextTestBase {

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

    private static ExperimentVariant variant(Experiment experiment, String key, boolean control, int internalId) {
        ExperimentVariant variant = new ExperimentVariant();
        variant.setExperiment(experiment);
        variant.setKey(key);
        variant.setName(key);
        variant.setControl(control);
        variant.setInternalId(internalId);
        return variant;
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
        assertThat(JsonMapper.builder().build().readTree(response.body())
                .path("invites").path(0).path("ownerUsername").asString()).isEqualTo("boundaries-inviter");
    }
}
