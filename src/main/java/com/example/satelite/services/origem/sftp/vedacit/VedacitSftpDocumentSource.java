package com.example.satelite.services.origem.sftp.vedacit;

import java.util.Optional;

/** Fonte documental isolada da Vedacit. A implementação SFTP não conhece SOAP nem regras de auditoria. */
public interface VedacitSftpDocumentSource {
    Optional<VedacitSftpDocument> buscarXmlCte(String chaveCte, String chaveNfe);

    Optional<VedacitSftpDocument> buscarComprovante(String chaveCte, String chaveNfe);
}
