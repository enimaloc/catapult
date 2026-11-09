package fr.enimaloc.catapult.experiment;

import fr.enimaloc.catapult.domain.experiment.Experiment;
import fr.enimaloc.catapult.repository.experiment.ExperimentRepository;
import fr.enimaloc.catapult.service.experiment.ExperimentService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ExperimentSynchronizerTest {

    @ExperimentSpec(
            key = "test.active", name = "Active at boot",
            variants = @ExperimentSpec.Variant(key = "control", name = "Control", weight = 100, control = true),
            initialStatus = Experiment.Status.ACTIVE)
    static class ActiveSpec {}

    @ExperimentSpec(
            key = "test.default", name = "Default status",
            variants = @ExperimentSpec.Variant(key = "control", name = "Control", weight = 100, control = true))
    static class DefaultSpec {}

    @Test
    void createsExperiment_withTheSpecsInitialStatus() {
        List<Experiment> saved = new ArrayList<>();
        ExperimentRepository experimentRepository = proxy(ExperimentRepository.class, (method, args) ->
                switch (method.getName()) {
                    case "findByKey" -> Optional.empty();
                    case "findAll" -> List.of();
                    case "save" -> {
                        saved.add((Experiment) args[0]);
                        yield args[0];
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Map<String, Object> beans = new LinkedHashMap<>();
        beans.put("active", new ActiveSpec());
        beans.put("default", new DefaultSpec());
        ApplicationContext context = proxy(ApplicationContext.class, (method, args) ->
                switch (method.getName()) {
                    case "getBeansWithAnnotation" -> beans;
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        // markKnown only records keys in memory, so the service needs no collaborators here.
        ExperimentService experimentService = new ExperimentService(null, null, null, null, null, null);
        new ExperimentSynchronizer(experimentRepository, experimentService, context, null, null)
                .run(new DefaultApplicationArguments());

        assertThat(saved).extracting(Experiment::getKey, Experiment::getStatus)
                .containsExactly(
                        tuple("test.active", Experiment.Status.ACTIVE),
                        tuple("test.default", Experiment.Status.DRAFT));
    }

    private interface Handler {
        Object handle(Method method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.handle(method, args));
    }
}
