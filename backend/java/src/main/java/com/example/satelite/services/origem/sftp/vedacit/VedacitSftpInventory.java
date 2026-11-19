package com.example.satelite.services.origem.sftp.vedacit;

import java.util.List;

/** Resultado do inventário sem conteúdo dos arquivos e com rejeições auditáveis. */
public record VedacitSftpInventory(
        List<VedacitSftpDocument> documentosValidos,
        List<DocumentoRejeitado> rejeitados
) {
    public VedacitSftpInventory {
        documentosValidos = documentosValidos == null ? List.of() : List.copyOf(documentosValidos);
        rejeitados = rejeitados == null ? List.of() : List.copyOf(rejeitados);
    }

    public record DocumentoRejeitado(String caminhoRelativo, String chaveCte, String chaveNfe, String motivo) { }
}
