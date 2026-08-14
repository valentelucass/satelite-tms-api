package com.example.satelite.services.origem.sftp.vedacit;

import java.time.Instant;

public record VedacitSftpDocument(
        Tipo tipo,
        String caminhoRelativo,
        String chaveCte,
        String chaveNfe,
        long tamanhoBytes,
        Instant modificadoEm,
        byte[] conteudo
) {
    public enum Tipo { XML_CTE, COMPROVANTE }
}
