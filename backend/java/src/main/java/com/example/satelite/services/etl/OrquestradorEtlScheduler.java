package com.example.satelite.services.etl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(
        "'${APP_SCHEDULER_ENABLED:true}'.equalsIgnoreCase('true') "
                + "&& !'${APP_CICLO_UNICO:${ciclo_unico:false}}'.equalsIgnoreCase('true') "
                + "&& !'${retroactive.enabled:false}'.equalsIgnoreCase('true') "
                + "&& !'${RETROACTIVE_ENABLED:false}'.equalsIgnoreCase('true')"
)
public class OrquestradorEtlScheduler {

    private final OrquestradorEtlService orquestradorEtlService;

    public OrquestradorEtlScheduler(OrquestradorEtlService orquestradorEtlService) {
        this.orquestradorEtlService = orquestradorEtlService;
    }

    @Scheduled(fixedDelayString = "${INTEGRATION_SCHEDULER_INTERVAL_MS:60000}")
    public void executarFluxosAgendados() {
        orquestradorEtlService.executarFluxos();
    }
}
