package com.example.satelite.services.origem.sftp.vedacit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class VedacitSftpClientFactoryTest {
    @Test
    void criaUmClienteIsoladoParaCadaPerfilAtivo() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("SFTP_CLIENTS_ENABLED", "true")
                .withProperty("SFTP_CLIENTS_IDS", "VEDACIT,CLIENTE_B");
        perfil(environment, "VEDACIT", "/g_rodogarcia/VEDACIT");
        perfil(environment, "CLIENTE_B", "/g_cliente_b/CLIENTE_B");

        var clientes = new VedacitSftpClientFactory(new SftpClientesProperties(environment)).criarClientesHabilitados();

        assertEquals(2, clientes.size());
        assertEquals("VEDACIT", clientes.get(0).identificador());
        assertEquals("CLIENTE_B", clientes.get(1).cliente().identificadorCliente());
    }

    private void perfil(MockEnvironment environment, String id, String caminho) {
        String prefixo = "SFTP_CLIENT_" + id + "_";
        String base = caminho.substring(0, caminho.lastIndexOf('/'));
        environment.withProperty(prefixo + "ENABLED", "true")
                .withProperty(prefixo + "HOST", "sftp." + id.toLowerCase() + ".local")
                .withProperty(prefixo + "USERNAME", "operador")
                .withProperty(prefixo + "PASSWORD", "segredo-de-teste")
                .withProperty(prefixo + "BASE_PATH", base)
                .withProperty(prefixo + "PATH", caminho)
                .withProperty(prefixo + "HOST_KEY_SHA256", "SHA256:teste");
    }
}
