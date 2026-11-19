package com.example.satelite.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ReconciliacaoClientesConfigTest {
    @Test void clientesLocaisNaoExigemFonteSftpVedacit() {
        var env=new MockEnvironment().withProperty("RECONCILIACAO_CLIENTES","PPG,SELIA,SUPPORTE");
        assertDoesNotThrow(()->new ReconciliacaoClientesConfig(env).validarIsolamento());
    }
    @Test void flagAntigaNaoPodeAtivarSupervisorDuplicado() {
        var env=new MockEnvironment().withProperty("VEDACIT_RECONCILIACAO_ENABLED","true");
        assertThrows(IllegalStateException.class,()->new ReconciliacaoClientesConfig(env).validarIsolamento());
    }
    @Test void recusaProcessoDoDashboardMesmoQueFlagNoturnaSejaAtivada() {
        var env=new MockEnvironment().withProperty("APP_DASHBOARD_API_ONLY","true").withProperty("VEDACIT_SFTP_RECEIPT_ONLY","true");
        assertThrows(IllegalStateException.class,()->new ReconciliacaoClientesConfig(env).validarIsolamento());
    }
    @Test void recusaWorkerQueEncerraAJvm() {
        var env=new MockEnvironment().withProperty("work.sftp-clientes.enabled","true").withProperty("VEDACIT_SFTP_RECEIPT_ONLY","true");
        assertThrows(IllegalStateException.class,()->new ReconciliacaoClientesConfig(env).validarIsolamento());
    }
    @Test void aceitaSupervisorResidenteSemOutrosFluxos() {
        var env=new MockEnvironment().withProperty("VEDACIT_SFTP_RECEIPT_ONLY","true");
        assertDoesNotThrow(()->new ReconciliacaoClientesConfig(env).validarIsolamento());
    }
    @Test void aliasLegadoDeCicloUnicoTambemEhRecusado() {
        var env=new MockEnvironment().withProperty("ciclo_unico","true").withProperty("VEDACIT_SFTP_RECEIPT_ONLY","true");
        assertThrows(IllegalStateException.class,()->new ReconciliacaoClientesConfig(env).validarIsolamento());
    }
}
