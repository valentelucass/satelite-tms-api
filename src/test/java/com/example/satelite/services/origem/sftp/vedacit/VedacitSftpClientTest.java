package com.example.satelite.services.origem.sftp.vedacit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VedacitSftpClientTest {
    @Test
    void naoAbreConexaoQuandoSftpEstaDesabilitado() {
        VedacitSftpClient client = new VedacitSftpClient(
                false, "host-invalido", 22, "usuario", "segredo", "/g_rodogarcia",
                "/g_rodogarcia/VEDACIT", "SHA256:invalida", 1024, 60);

        assertTrue(client.buscarXmlCte("35260760960473000758570030000521251702802407",
                "35260760642774001209550010002330311658124736").isEmpty());
    }

    @Test
    void verificacaoDeDisponibilidadeNaoAbreConexaoQuandoSftpEstaDesabilitado() {
        VedacitSftpClient client = new VedacitSftpClient(
                false, "host-invalido", 22, "usuario", "segredo", "/g_rodogarcia",
                "/g_rodogarcia/VEDACIT", "SHA256:invalida", 1024, 60);

        client.verificarDisponibilidade();
    }
}
