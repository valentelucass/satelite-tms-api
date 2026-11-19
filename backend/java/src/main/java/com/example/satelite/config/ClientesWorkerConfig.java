package com.example.satelite.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "APP_CLIENTES_WORKER_ENABLED", havingValue = "true")
public class ClientesWorkerConfig {

    // A validação ocorre antes de criar o servidor, os adaptadores e as tarefas agendadas.
    @Bean
    static BeanFactoryPostProcessor validarPerfilClientes(Environment env) {
        return factory -> {
            exigir(env, "spring.main.web-application-type", "none");
            exigir(env, "APP_RECONCILIACAO_ENABLED", "true");
            exigir(env, "VEDACIT_SFTP_RECEIPT_ONLY", "true");
            // Ambos os serviços usam true quando a propriedade está ausente.
            exigir(env, "APP_SCHEDULER_ENABLED", "false");
            exigir(env, "APP_ETL_REPESCAGEM_ENABLED", "false");
            ReconciliacaoClientesConfig.validarIsolamento(env);
        };
    }

    private static void exigir(Environment env, String propriedade, String esperado) {
        if (!esperado.equalsIgnoreCase(env.getProperty(propriedade))) {
            throw new IllegalStateException("O worker de clientes exige " + propriedade + "=" + esperado);
        }
    }
}
