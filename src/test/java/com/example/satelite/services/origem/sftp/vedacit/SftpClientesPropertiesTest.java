package com.example.satelite.services.origem.sftp.vedacit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SftpClientesPropertiesTest {

    @Test
    void devolveSomentePerfisHabilitadosEValidados() {
        MockEnvironment environment = perfilValido(new MockEnvironment())
                .withProperty("SFTP_CLIENTS_IDS", "VEDACIT,CLIENTE_B")
                .withProperty("SFTP_CLIENT_CLIENTE_B_ENABLED", "false");

        var perfis = new SftpClientesProperties(environment).perfisHabilitados();

        assertEquals(1, perfis.size());
        assertEquals("VEDACIT", perfis.get(0).identificador());
        assertEquals("/g_rodogarcia/VEDACIT", perfis.get(0).diretorioCliente());
    }

    @Test
    void rejeitaIdentificadorDuplicado() {
        MockEnvironment environment = perfilValido(new MockEnvironment())
                .withProperty("SFTP_CLIENTS_IDS", "VEDACIT,vedacit");

        assertThrows(IllegalArgumentException.class,
                () -> new SftpClientesProperties(environment).perfisHabilitados());
    }

    @Test
    void rejeitaPerfilAtivoSemFingerprint() {
        MockEnvironment environment = perfilValido(new MockEnvironment())
                .withProperty("SFTP_CLIENT_VEDACIT_HOST_KEY_SHA256", "");

        assertThrows(IllegalStateException.class,
                () -> new SftpClientesProperties(environment).perfisHabilitados());
    }

    @Test
    void rejeitaSubpastaForaDaBase() {
        MockEnvironment environment = perfilValido(new MockEnvironment())
                .withProperty("SFTP_CLIENT_VEDACIT_PATH", "/outro-cliente");

        assertThrows(IllegalArgumentException.class,
                () -> new SftpClientesProperties(environment).perfisHabilitados());
    }

    private MockEnvironment perfilValido(MockEnvironment environment) {
        return environment
                .withProperty("SFTP_CLIENTS_ENABLED", "true")
                .withProperty("SFTP_CLIENTS_IDS", "VEDACIT")
                .withProperty("SFTP_CLIENT_VEDACIT_ENABLED", "true")
                .withProperty("SFTP_CLIENT_VEDACIT_HOST", "sftp.exemplo.local")
                .withProperty("SFTP_CLIENT_VEDACIT_USERNAME", "operador")
                .withProperty("SFTP_CLIENT_VEDACIT_PASSWORD", "segredo-de-teste")
                .withProperty("SFTP_CLIENT_VEDACIT_BASE_PATH", "/g_rodogarcia")
                .withProperty("SFTP_CLIENT_VEDACIT_PATH", "/g_rodogarcia/VEDACIT")
                .withProperty("SFTP_CLIENT_VEDACIT_HOST_KEY_SHA256", "SHA256:teste");
    }
}
