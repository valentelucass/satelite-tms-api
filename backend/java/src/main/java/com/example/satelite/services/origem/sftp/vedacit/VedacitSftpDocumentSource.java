package com.example.satelite.services.origem.sftp.vedacit;

import java.util.List;
import java.util.Optional;

/** Fonte documental isolada da Vedacit. A implementação SFTP não conhece SOAP nem regras de auditoria. */
public interface VedacitSftpDocumentSource {
    /** Origem real do documento fornecido; não habilita fallback nem altera política de envio. */
    default String origemComprovante() { return "SFTP"; }

    /** Referência técnica opcional da fonte explícita; não substitui chaves fiscais. */
    default String referenciaComprovante(String chaveCte, String chaveNfe) { return null; }

    Optional<VedacitSftpDocument> buscarXmlCte(String chaveCte, String chaveNfe);

    Optional<VedacitSftpDocument> buscarComprovante(String chaveCte, String chaveNfe);

    /** Lista somente comprovantes cujo nome contém a NF-e informada e duas chaves válidas. */
    default List<VedacitSftpDocument> buscarComprovantesPorNfe(String chaveNfe) {
        return List.of();
    }

    /** Inventário somente de metadados; o conteúdo não é baixado. */
    default List<VedacitSftpDocument> listarComprovantes() {
        return List.of();
    }
}
