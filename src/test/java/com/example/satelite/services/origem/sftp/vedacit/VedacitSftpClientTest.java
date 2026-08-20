package com.example.satelite.services.origem.sftp.vedacit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void preservaIdentificadorDoPerfilSemAbrirConexao() {
        SftpClientesProperties.Perfil perfil = new SftpClientesProperties.Perfil(
                "CLIENTE_A", "host-invalido", 22, "usuario", "segredo", "/g_rodogarcia",
                "/g_rodogarcia/CLIENTE_A", "SHA256:invalida", 10, 1024, 60
        );

        VedacitSftpClient client = new VedacitSftpClient(perfil);

        assertEquals("CLIENTE_A", client.identificadorCliente());
    }
}
