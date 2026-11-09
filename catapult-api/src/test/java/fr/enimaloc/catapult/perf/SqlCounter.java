package fr.enimaloc.catapult.perf;

import java.util.List;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Counts the SQL statements the current thread runs. MockMvc serves a request on the test's thread, so
 * {@link #reset()} before {@code perform} and {@link #count()} after it give the statements of that one request;
 * what schedulers or other threads run meanwhile is not counted. A batch counts as the statements it holds.
 */
public final class SqlCounter {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    private SqlCounter() {}

    /** Starts counting again from zero on this thread. */
    public static void reset() {
        COUNT.get()[0] = 0;
    }

    /** The statements this thread ran since its last {@link #reset()}. */
    public static int count() {
        return COUNT.get()[0];
    }

    static void record(ExecutionInfo execution, List<QueryInfo> queries) {
        int statements;
        if (!execution.isBatch()) {
            statements = 1;
        } else if (execution.getBatchSize() > 0) {
            statements = execution.getBatchSize();
        } else {
            statements = Math.max(1, queries.size());
        }
        COUNT.get()[0] += statements;
    }

    /** Wraps the application's {@link DataSource} so that every statement it runs goes through {@link SqlCounter}. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        static BeanPostProcessor sqlCountingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof DataSource dataSource) || bean instanceof ProxyDataSource) {
                        return bean;
                    }
                    return ProxyDataSourceBuilder.create(dataSource)
                            .name(beanName)
                            .countQuery()
                            .listener(new QueryExecutionListener() {
                                @Override
                                public void beforeQuery(ExecutionInfo execution, List<QueryInfo> queries) {
                                    // counted once it ran
                                }

                                @Override
                                public void afterQuery(ExecutionInfo execution, List<QueryInfo> queries) {
                                    record(execution, queries);
                                }
                            })
                            .build();
                }
            };
        }
    }
}
