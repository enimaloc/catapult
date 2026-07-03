package fr.enimaloc.catapult.experiment.targeting.resolver;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.experiment.targeting.AttributeResolver;
import fr.enimaloc.catapult.experiment.targeting.AttributeValue;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRepository;
import fr.enimaloc.catapult.repository.ExperimentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AssignedExperimentResolver implements AttributeResolver {

    private static final String PREFIX = "experiment:";

    private final ExperimentRepository experimentRepository;
    private final ExperimentAssignmentRepository assignmentRepository;

    @Override
    public boolean supports(String key) {
        return key.startsWith(PREFIX) && key.length() > PREFIX.length();
    }

    @Override
    public AttributeValue resolve(UserAccount user, String key) {
        String experimentKey = key.substring(PREFIX.length());
        return experimentRepository.findByKey(experimentKey)
            .map(exp -> assignmentRepository.findByExperimentAndUser(exp, user)
                .map(a -> AttributeValue.text(a.getVariant().getKey()))
                .orElse(AttributeValue.text("unassigned")))
            .orElse(AttributeValue.missing());
    }
}
