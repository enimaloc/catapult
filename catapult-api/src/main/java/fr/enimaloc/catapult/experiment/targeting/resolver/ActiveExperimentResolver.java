package fr.enimaloc.catapult.experiment.targeting.resolver;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.experiment.targeting.AttributeResolver;
import fr.enimaloc.catapult.experiment.targeting.AttributeValue;
import fr.enimaloc.catapult.repository.ExperimentAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ActiveExperimentResolver implements AttributeResolver {

    private final ExperimentAssignmentRepository assignmentRepository;

    @Override
    public boolean supports(String key) {
        return key.equals("in_active_experiment") || key.equals("active_experiment_count");
    }

    @Override
    public AttributeValue resolve(UserAccount user, String key) {
        int count = assignmentRepository.findActiveByUser(user).size();
        return switch (key) {
            case "active_experiment_count" -> AttributeValue.number(count);
            case "in_active_experiment"    -> AttributeValue.number(count > 0 ? 1 : 0);
            default                        -> AttributeValue.missing();
        };
    }
}
