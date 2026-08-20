package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SftpDocumentoLockServiceTest {
    private static final String NFE = "35260760642774001209550010002330311658124736";
    private static final String CTE = "35260760960473000758570030000521251702802407";

    @Test
    void criaRecursoDeterministicoPorClienteENotas() {
        assertEquals(
                "SATELITE_TMS:VEDACIT:SFTP:CLIENTE_A:" + NFE + ":" + CTE,
                SftpDocumentoLockService.recurso("cliente_a", NFE, CTE)
        );
    }

    @Test
    void rejeitaClienteOuDocumentoInvalidos() {
        assertThrows(IllegalArgumentException.class,
                () -> SftpDocumentoLockService.recurso("cliente-a", NFE, CTE));
        assertThrows(IllegalArgumentException.class,
                () -> SftpDocumentoLockService.recurso("CLIENTE_A", "invalida", CTE));
    }
}
