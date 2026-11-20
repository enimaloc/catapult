package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchService;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts the dry-run gate in front of every {@link TwitchService} and {@link TwitchChatService} bean: the real clients
 * ({@code TwitchServiceImpl}, the EventSub or IRC chat transport) and, with the simulation on, the simulated ones
 * ({@code SimulatedTwitchService}, {@code SimulatedTwitchChatService}, {@code @Primary}). Whichever bean a consumer
 * resolves, by interface or by concrete class, is gated, and the one consumers get by interface (the simulated one
 * when present) is gated before the simulation: a member in dry run reaches neither Twitch nor the simulator.
 * <p>
 * Each bean is replaced by a class-based proxy of itself, so that injection by concrete class
 * ({@code ObjectProvider<TwitchServiceImpl>}, {@code ObjectProvider<EventSubTwitchChatService>}...) and the bean's own
 * callbacks ({@code @EventListener}, {@code @PreDestroy}) keep working; the interface's methods go through
 * {@link DryRunTwitchService} / {@link DryRunTwitchChatService}, every other method straight to the bean. Gating a
 * bean behind another gated one costs one more set lookup: when the outer gate holds a write, the inner one is never
 * reached. The gate is looked up on each call, so wrapping creates no dependency on it.
 */
@Component
public class DryRunTwitchWrapping implements BeanPostProcessor, BeanFactoryAware {

    private ObjectProvider<DryRunGate> gate;
    private ConfigurableListableBeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.gate = beanFactory.getBeanProvider(DryRunGate.class);
        this.beanFactory = beanFactory instanceof ConfigurableListableBeanFactory configurable ? configurable : null;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DryRunTwitchService || bean instanceof DryRunTwitchChatService) {
            return bean;
        }
        if (bean instanceof TwitchService twitch) {
            return gated(bean, beanName, TwitchService.class, new DryRunTwitchService(twitch, gate::getObject));
        }
        if (bean instanceof TwitchChatService chat) {
            return gated(bean, beanName, TwitchChatService.class, new DryRunTwitchChatService(chat, gate::getObject));
        }
        return bean;
    }

    private Object gated(Object bean, String beanName, Class<?> api, Object decorator) {
        if (bean instanceof Advised) {
            // Its target would be called past the gate by the other advices: refuse to start rather than leak
            throw new IllegalStateException("Bean '" + beanName + "' is already proxied: the dry-run gate cannot"
                    + " be put in front of it");
        }
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new Gate(api, decorator));
        if (beanFactory != null && beanFactory.containsBeanDefinition(beanName)) {
            // as Spring's own proxies do: the bean's annotated methods are then looked up on its class
            beanFactory.getMergedBeanDefinition(beanName)
                    .setAttribute(AutoProxyUtils.ORIGINAL_TARGET_CLASS_ATTRIBUTE, bean.getClass());
        }
        return factory.getProxy(bean.getClass().getClassLoader());
    }

    /** Sends the calls of {@code api}'s methods to {@code decorator}, which delegates to the bean itself. */
    private static final class Gate implements MethodInterceptor {

        private final Class<?> api;
        private final Object decorator;
        private final Map<Method, Optional<Method>> apiMethods = new ConcurrentHashMap<>();

        Gate(Class<?> api, Object decorator) {
            this.api = api;
            this.decorator = decorator;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Optional<Method> apiMethod = apiMethods.computeIfAbsent(invocation.getMethod(), this::apiMethod);
            if (apiMethod.isEmpty()) {
                return invocation.proceed();
            }
            try {
                return apiMethod.get().invoke(decorator, invocation.getArguments());
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private Optional<Method> apiMethod(Method method) {
            try {
                return Optional.of(api.getMethod(method.getName(), method.getParameterTypes()));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }
    }
}
