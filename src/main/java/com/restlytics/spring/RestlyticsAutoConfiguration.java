package com.restlytics.spring;

import com.restlytics.HttpTransport;
import com.restlytics.NullTransport;
import com.restlytics.RestlyticsConfig;
import com.restlytics.Tracer;
import com.restlytics.Transport;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import jakarta.servlet.Filter;

/**
 * REVIEW-ONLY (depends on Spring Boot autoconfigure + servlet; cannot compile offline).
 *
 * <p>Spring Boot auto-configuration. Registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * so it activates with zero code when the starter is on the classpath. Honors
 * {@code restlytics.enabled} (default true) and binds all {@code restlytics.*}
 * properties into {@link RestlyticsConfig}.
 *
 * <p>It wires:
 * <ul>
 *   <li>{@link Transport} — {@link HttpTransport} normally, {@link NullTransport} when
 *       {@code restlytics.transport=null} or no key is configured;</li>
 *   <li>{@link Tracer} — the per-request orchestrator;</li>
 *   <li>{@link RestlyticsFilter} — registered first in the chain so the SERVER span
 *       brackets the whole request;</li>
 *   <li>a Hibernate properties customizer installing {@link RestlyticsStatementInspector}
 *       (only when Hibernate is present).</li>
 * </ul>
 *
 * <p>All beans are {@code @ConditionalOnMissingBean} so apps can override any piece.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "restlytics", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties
public class RestlyticsAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "restlytics")
    @ConditionalOnMissingBean
    public RestlyticsConfig restlyticsConfig() {
        return new RestlyticsConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public Transport restlyticsTransport(RestlyticsConfig config) {
        boolean nullTransport = "null".equalsIgnoreCase(config.getTransport()) || !config.isEnabled();
        if (nullTransport) {
            return new NullTransport();
        }
        return new HttpTransport(config.getIngestUrl(), config.getKey(), config.getTimeoutMs());
    }

    @Bean
    @ConditionalOnMissingBean
    public Tracer restlyticsTracer(Transport transport, RestlyticsConfig config) {
        return new Tracer(transport, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<Filter> restlyticsFilterRegistration(Tracer tracer, RestlyticsConfig config) {
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new RestlyticsFilter(tracer, config));
        reg.addUrlPatterns("/*");
        // Run first so the SERVER span brackets the entire request, including downstream
        // filters/handlers.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setName("restlyticsFilter");
        return reg;
    }

    /**
     * Installs the Hibernate {@link RestlyticsStatementInspector} when Hibernate is on
     * the classpath. {@code HibernatePropertiesCustomizer} lets us add the inspector
     * without replacing the app's JPA config. Guarded by {@code @ConditionalOnClass} so
     * the auto-config still loads in apps without Hibernate.
     */
    @Bean
    @ConditionalOnClass(name = "org.hibernate.resource.jdbc.spi.StatementInspector")
    @ConditionalOnMissingBean(name = "restlyticsHibernateCustomizer")
    public org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer restlyticsHibernateCustomizer(
            Tracer tracer, RestlyticsConfig config) {
        RestlyticsStatementInspector inspector = new RestlyticsStatementInspector(tracer, config);
        return (props) -> props.put("hibernate.session_factory.statement_inspector", inspector);
    }
}
