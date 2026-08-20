package com.example.satelite.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Defesa em profundidade do backend do Dashboard: nenhuma rotina de linha de
 * comando pode acionar integrações quando a instância é somente leitura.
 */
@Configuration
@ConditionalOnProperty(name = "APP_DASHBOARD_API_ONLY", havingValue = "true")
public class DashboardApiOnlyConfig {
    private static final Logger log = LoggerFactory.getLogger(DashboardApiOnlyConfig.class);

    @Bean
    BeanPostProcessor bloquearRunnersDeIntegracao() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!(bean instanceof CommandLineRunner)) {
                    return bean;
                }
                return (CommandLineRunner) argumentos -> log.info(
                        "[DASHBOARD_API_ONLY] runner {} bloqueado; integração externa não permitida.", beanName
                );
            }
        };
    }
}
