package com.example.satelite.services.origem.sftp.vedacit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VedacitSftpPathPolicyTest {
    private static final String CTE = "35260760960473000758570030000521251702802407";
    private static final String NFE = "35260760642774001209550010002330311658124736";

    @Test
    void aceitaSomenteDiretoriosVedacitAbaixoDaBase() {
        assertEquals("/g_rodogarcia/VEDACIT/xml", VedacitSftpPathPolicy.caminhoXml("/g_rodogarcia", "/g_rodogarcia/VEDACIT"));
        assertThrows(IllegalArgumentException.class, () -> VedacitSftpPathPolicy.validarDiretorioCliente("/g_rodogarcia", "/"));
        assertThrows(IllegalArgumentException.class, () -> VedacitSftpPathPolicy.validarDiretorioCliente("/g_rodogarcia", "/g_outro/VEDACIT"));
    }

    @Test
    void correlacionaSomenteComprovanteComAmbasAsChavesEExtensaoPermitida() {
        assertTrue(VedacitSftpPathPolicy.nomeComprovanteCorresponde("420789_" + CTE + "_" + NFE + ".jpg", CTE, NFE));
        assertTrue(VedacitSftpPathPolicy.nomeComprovanteCorresponde("420789_" + CTE + "_" + NFE + ".png", CTE, NFE));
        assertFalse(VedacitSftpPathPolicy.nomeComprovanteCorresponde("420789_" + CTE + "_" + NFE + ".exe", CTE, NFE));
        assertFalse(VedacitSftpPathPolicy.nomeComprovanteCorresponde("420789_" + NFE + "_" + CTE + ".jpg", CTE, NFE));
    }
}
