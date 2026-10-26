package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.common.dto.admin.AdminNotificationCreateBody;
import fr.enimaloc.catapult.common.dto.admin.AudienceCountDto;
import fr.enimaloc.catapult.common.dto.admin.AudienceCountRequest;
import fr.enimaloc.catapult.common.dto.notification.NotificationDto;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.domain.experiment.ExperimentVariant;
import fr.enimaloc.catapult.domain.notification.Notification;
import fr.enimaloc.catapult.repository.chatcommand.ChatCommandDefinitionRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.repository.experiment.ExperimentVariantRepository;
import fr.enimaloc.catapult.repository.notification.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** An admin's notifications: who a request reaches (everyone, the chat-command streamers, an experiment) and removal. */
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    /** What a creation did: told {@code sent} members, or made the one notification. */
    public sealed interface Created permits Sent, Single {}

    public record Sent(int sent) implements Created {}

    public record Single(NotificationDto dto) implements Created {}

    private final NotificationService notifications;
    private final NotificationRepository repo;
    private final ChatCommandDefinitionRepository commandRepository;
    private final ExperimentRepository experimentRepository;
    private final ExperimentVariantRepository variantRepository;
    private final ExperimentAssignmentRepository assignmentRepository;

    /**
     * For {@link AdminNotificationCreateBody#CHAT_COMMANDS}, one notification per streamer; for
     * {@link AdminNotificationCreateBody#EXPERIMENT}, one shared by the participants; otherwise the notification itself.
     */
    public Created create(AdminNotificationCreateBody body, UserAccount admin) {
        NotificationService.CreateRequest req = new NotificationService.CreateRequest(
                body.title(), body.body(), body.severity(),
                body.ctaUrl(), body.ctaLabel(), body.expiresAt(), body.targetUserId());
        if (AdminNotificationCreateBody.CHAT_COMMANDS.equals(body.audience())) {
            return new Sent(notifications.createForEach(req, admin, commandRepository.findActiveUsersWithEnabledCommands()));
        }
        if (AdminNotificationCreateBody.EXPERIMENT.equals(body.audience())) {
            Experiment experiment = experimentOf(body.experimentId());
            List<UserAccount> recipients = body.variantId() == null
                    ? assignmentRepository.findNotifiableUsers(experiment)
                    : assignmentRepository.findNotifiableUsersByVariant(experiment, variantOf(experiment, body.variantId()).getId());
            return new Sent(notifications.createForAll(req, admin, recipients));
        }
        return new Single(notifications.create(req, admin));
    }

    /** How many members an audience would reach; experiment audiences only. */
    public AudienceCountDto audienceCount(AudienceCountRequest body) {
        if (!AdminNotificationCreateBody.EXPERIMENT.equals(body.audience())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only the experiment audience can be counted");
        }
        Experiment experiment = experimentOf(body.experimentId());
        long count = body.variantId() == null
                ? assignmentRepository.countNotifiableUsers(experiment)
                : assignmentRepository.countNotifiableUsersByVariant(experiment, variantOf(experiment, body.variantId()).getId());
        return new AudienceCountDto(count);
    }

    public void delete(UUID id) {
        Notification n = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.delete(n);
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
}
