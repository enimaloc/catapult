package fr.enimaloc.catapult.experiment.thymeleaf;

import fr.enimaloc.catapult.domain.ExperimentAssignment;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.ExperimentService;

import java.util.List;

public class ExperimentExpressionObject {

    private final ExperimentService service;
    private final UserAccount user;
    private List<ExperimentAssignment> cachedAssignments;

    ExperimentExpressionObject(ExperimentService service, UserAccount user) {
        this.service = service;
        this.user = user;
    }

    public List<ExperimentAssignment> getAll() {
        return assignments();
    }

    public String getVariant(String key) {
        if (user == null) return null;
        service.ensureExists(key);
        return assignments().stream()
                .filter(a -> a.getExperiment().getKey().equals(key))
                .findFirst()
                .map(a -> a.getVariant().getKey())
                .orElse(null);
    }

    public boolean isInVariant(String key, String variant) {
        if (user == null) return false;
        service.ensureExists(key, variant);
        return assignments().stream()
                .anyMatch(a -> a.getExperiment().getKey().equals(key)
                        && a.getVariant().getKey().equals(variant));
    }

    public boolean isRolledOut(String key) {
        if (user == null) return false;
        service.ensureExists(key);
        return assignments().stream()
                .filter(a -> a.getExperiment().getKey().equals(key))
                .findFirst()
                .map(a -> !a.getVariant().isControl())
                .orElse(false);
    }

    private List<ExperimentAssignment> assignments() {
        if (user == null) return List.of();
        if (cachedAssignments == null) {
            cachedAssignments = service.getActiveAssignments(user);
        }
        return cachedAssignments;
    }
}
