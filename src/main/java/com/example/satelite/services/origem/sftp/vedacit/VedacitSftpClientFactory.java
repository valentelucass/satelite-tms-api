package com.example.satelite.services.origem.sftp.vedacit;

import java.util.List;

import org.springframework.stereotype.Component;

/** Cria clientes SFTP efêmeros e isolados, um para cada perfil habilitado. */
@Component
public class VedacitSftpClientFactory {
    private final SftpClientesProperties properties;

    public VedacitSftpClientFactory(SftpClientesProperties properties) {
        this.properties = properties;
    }

    public List<ClienteSftp> criarClientesHabilitados() {
        return properties.perfisHabilitados().stream()
                .map(perfil -> new ClienteSftp(perfil.identificador(), new VedacitSftpClient(perfil)))
                .toList();
    }

    public record ClienteSftp(String identificador, VedacitSftpClient cliente) { }
}
