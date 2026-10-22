package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.common.dto.admin.AudienceCountDto;
import fr.enimaloc.catapult.common.dto.admin.AudienceCountRequest;
import fr.enimaloc.catapult.common.dto.notification.AdminNotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewDto;
import fr.enimaloc.catapult.common.dto.notification.NotificationPreviewRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.domain.notification.Notification;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentVariantRepository;
import fr.enimaloc.catapult.repository.notification.NotificationRepository;
import fr.enimaloc.catapult.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/notifications")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminNotificationsController {

    private final NotificationService service;
    private final NotificationRepository repo;
    private final ApiUserResolver userResolver;
    private final fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository commandRepository;
    private final ExperimentRepository experimentRepository;
    private final ExperimentVariantRepository variantRepository;
    private final ExperimentAssignmentRepository assignmentRepository;

    /**
     * 201 with the notification; for {@link AdminNotificationCreateBody#CHAT_COMMANDS}, 201 with how
     * many streamers were told (one notification each); for {@link AdminNotificationCreateBody#EXPERIMENT},
     * 201 with how many participants share one notification.
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody AdminNotificationCreateBody body, @AuthenticationPrincipal Jwt jwt) {
        UserAccount admin = userResolver.viewerByTwitchId(jwt);
        NotificationService.CreateRequest req = new NotificationService.CreateRequest(
                body.title(), body.body(), body.severity(),
                body.ctaUrl(), body.ctaLabel(), body.expiresAt(), body.targetUserId());
        if (AdminNotificationCreateBody.CHAT_COMMANDS.equals(body.audience())) {
            int sent = service.createForEach(req, admin, commandRepository.findActiveUsersWithEnabledCommands());
            return ResponseEntity.status(HttpStatus.CREATED).body(java.util.Map.of("sent", sent));
        }
        if (AdminNotificationCreateBody.EXPERIMENT.equals(body.audience())) {
            Experiment experiment = experimentOf(body.experimentId());
            List<UserAccount> recipients = body.variantId() == null
                    ? assignmentRepository.findNotifiableUsers(experiment)
                    : assignmentRepository.findNotifiableUsersByVariant(experiment, variantOf(experiment, body.variantId()).getId());
            int sent = service.createForAll(req, admin, recipients);
            return ResponseEntity.status(HttpStatus.CREATED).body(java.util.Map.of("sent", sent));
        }
        NotificationDto dto = service.create(req, admin);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    /** How many members an audience would reach, so the admin sees it before sending; experiment audiences only. */
    @PostMapping("/audience-count")
    public AudienceCountDto audienceCount(@RequestBody AudienceCountRequest body) {
        if (!AdminNotificationCreateBody.EXPERIMENT.equals(body.audience())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only the experiment audience can be counted");
        }
        Experiment experiment = experimentOf(body.experimentId());
        long count = body.variantId() == null
                ? assignmentRepository.countNotifiableUsers(experiment)
                : assignmentRepository.countNotifiableUsersByVariant(experiment, variantOf(experiment, body.variantId()).getId());
        return new AudienceCountDto(count);
    }

    private Experiment experimentOf(UUID experimentId) {
        if (experimentId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An experiment is required");
        return experimentRepository.findById(experimentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown experiment"));
    }

    /** The variant, which must belong to {@code experiment}: another experiment's is as unknown as a made-up id. */
    private ExperimentVariant variantOf(Experiment experiment, UUID variantId) {
        return variantRepository.findById(variantId)
                .filter(v -> v.getExperiment().getId().equals(experiment.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown variant"));
    }

    @GetMapping
    public Page<AdminNotificationDto> list(Pageable pageable) {
        return service.adminPage(pageable);
    }

    @PostMapping("/preview")
    public NotificationPreviewDto preview(@RequestBody NotificationPreviewRequest body) {
        return service.preview(body.body());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        Notification n = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.delete(n);
    }

}
