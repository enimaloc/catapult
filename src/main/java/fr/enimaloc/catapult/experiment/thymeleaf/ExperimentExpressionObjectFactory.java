package fr.enimaloc.catapult.experiment.thymeleaf;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.ExperimentService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.util.Set;

class ExperimentExpressionObjectFactory implements IExpressionObjectFactory {

    private static final String NAME = "experiments";
    private final ExperimentService experimentService;

    ExperimentExpressionObjectFactory(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @Override
    public Set<String> getAllExpressionObjectNames() {
        return Set.of(NAME);
    }

    @Override
    public boolean isCacheable(String expressionObjectName) {
        // true = Thymeleaf reuses the same instance within one rendering context,
        // letting cachedAssignments in ExperimentExpressionObject do its job
        return true;
    }

    @Override
    public Object buildObject(IExpressionContext context, String expressionObjectName) {
        if (!NAME.equals(expressionObjectName)) return null;
        return new ExperimentExpressionObject(experimentService, resolveUser());
    }

    private UserAccount resolveUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CatapultOAuth2User oauthUser) {
            return oauthUser.getUserAccount();
        }
        return null;
    }
}
