package com.example.satelite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClientesPacoteTest {
    @Test
    void workerContemIntegracoesEReconciliacaoSemRotasQueRecebemChamadas() {
        ClassLoader loader = ClientesApplication.class.getClassLoader();
        String base = "com/example/satelite/";
        assertNotNull(loader.getResource(base + "services/etl/ClientesWorker.class"));
        assertNotNull(loader.getResource(base + "services/etl/CicloSftpClientes.class"));
        assertNotNull(loader.getResource(base + "services/selia/SeliaPlpCorrelationService.class"));
        assertNotNull(loader.getResource(base + "services/reconciliacao/ReconciliacaoClientesScheduler.class"));
        assertNull(loader.getResource(base + "controllers/IntegracaoAuditoriaController.class"));
        assertNull(loader.getResource(base + "controllers/SeliaPreShipmentListController.class"));
        assertNull(loader.getResource(base + "services/selia/SeliaPreShipmentListService.class"));
    }
}
