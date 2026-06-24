package com.example.satelite.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnExpression(
        "'${APP_SCHEDULER_ENABLED:true}'.equalsIgnoreCase('true') "
                + "&& !'${APP_CICLO_UNICO:${ciclo_unico:false}}'.equalsIgnoreCase('true') "
                + "&& !'${retroactive.enabled:false}'.equalsIgnoreCase('true') "
                + "&& !'${RETROACTIVE_ENABLED:false}'.equalsIgnoreCase('true')"
)
public class SchedulerConfig {
}
