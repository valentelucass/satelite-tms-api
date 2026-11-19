package com.example.satelite.services.vedacit;

import java.util.Optional;

/** Fonte adicional explícita para XMLs oficiais recuperados fora da API REST. */
public interface VedacitXmlDocumentSource {
    Optional<Documento> buscar(String chaveCte, String chaveNfe);

    record Documento(byte[] conteudo, String sha256, String origem) {
        public Documento {
            conteudo = conteudo.clone();
        }
        @Override public byte[] conteudo() { return conteudo.clone(); }
    }
}
