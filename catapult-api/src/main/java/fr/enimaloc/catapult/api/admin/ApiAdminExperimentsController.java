package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.common.dto.admin.AssignRequest;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentAssignmentsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentRebalanceDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSettingsDto;
import fr.enimaloc.catapult.common.dto.experiment.ExperimentSummaryDto;
import fr.enimaloc.catapult.common.dto.experiment.MoveAssignmentRequest;
import fr.enimaloc.catapult.service.experiment.ExperimentAdminService;
import fr.enimaloc.catapult.service.experiment.ExperimentAdminService.AddOverrideRequest;
import fr.enimaloc.catapult.service.experiment.ExperimentAdminService.AddRuleRequest;
import fr.enimaloc.catapult.service.experiment.ExperimentAdminService.ExperimentDetailData;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/experiments")
@RequiredArgsConstructor
public class ApiAdminExperimentsController {

    private final ExperimentAdminService experimentAdminService;

    @GetMapping
    public List<ExperimentSummaryDto> list() {
        return experimentAdminService.list();
    }

    @GetMapping("/{id}/settings")
    public ExperimentSettingsDto settings(@PathVariable UUID id) {
        return experimentAdminService.settings(id);
    }

    @GetMapping("/{id}")
    public ExperimentDetailData detail(@PathVariable UUID id) {
        return experimentAdminService.detail(id);
    }

    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable UUID id) {
        experimentAdminService.activate(id);
    }

    @PostMapping("/{id}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(@PathVariable UUID id) {
        experimentAdminService.pause(id);
    }

    @PostMapping("/{id}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@PathVariable UUID id) {
        experimentAdminService.end(id);
    }

    @PostMapping("/{id}/assign")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assignUser(@PathVariable UUID id, @RequestBody AssignRequest body) {
        experimentAdminService.assign(id, body.twitchUsername());
    }

    @PutMapping("/{id}/variants/weights")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateWeights(@PathVariable UUID id, @RequestBody Map<UUID, Integer> weights) {
        experimentAdminService.updateWeights(id, weights);
    }

    /** What saving these weights would move, without saving or moving anything. */
    @PostMapping("/{id}/variants/weights/preview")
    public ExperimentRebalanceDto previewWeights(@PathVariable UUID id, @RequestBody Map<UUID, Integer> weights) {
        return experimentAdminService.previewWeights(id, weights);
    }

    @PostMapping("/{id}/rules")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addRule(@PathVariable UUID id, @RequestBody AddRuleRequest body) {
        experimentAdminService.addRule(id, body);
    }

    @DeleteMapping("/{id}/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id, @PathVariable UUID ruleId) {
        experimentAdminService.deleteRule(id, ruleId);
    }

    @PostMapping("/{id}/overrides")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addOverride(@PathVariable UUID id, @RequestBody AddOverrideRequest body) {
        experimentAdminService.addOverride(id, body);
    }

    @DeleteMapping("/{id}/overrides/{overrideId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOverride(@PathVariable UUID id, @PathVariable UUID overrideId) {
        experimentAdminService.deleteOverride(id, overrideId);
    }

    /**
     * An experiment's participants, one page at a time (there can be many), plus how many each variant holds
     * whatever the filter. {@code variant} narrows to one variant, {@code q} to usernames containing it.
     */
    @GetMapping("/{id}/assignments")
    public ExperimentAssignmentsDto assignments(@PathVariable UUID id,
                                                @RequestParam(required = false) UUID variant,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "0") int page) {
        return experimentAdminService.assignments(id, variant, q, page);
    }

    /**
     * Moves a participant to another variant: a USER FORCE_VARIANT override keeps them there on later
     * evaluations, and their assignment is corrected now so the list shows it at once.
     */
    @PutMapping("/{id}/assignments/{memberId}/variant")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveAssignment(@PathVariable UUID id, @PathVariable UUID memberId,
                               @RequestBody MoveAssignmentRequest body) {
        experimentAdminService.moveAssignment(id, memberId, body);
    }

    @PostMapping("/{id}/reassign")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reassign(@PathVariable UUID id) {
        experimentAdminService.reassign(id);
    }
}
