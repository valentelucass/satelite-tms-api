package com.example.satelite.services.auditoria;

import java.util.UUID;
import org.springframework.stereotype.Service;
import com.example.satelite.repositories.IntegracaoEventoRepository;
import com.example.satelite.services.etl.AuditoriaDataHoraService;

/** Executado imediatamente antes da chamada externa, após validação e aquisição do semáforo. */
@Service
public class IntegracaoTransmissaoAuditoriaService {
    private final IntegracaoEventoRepository repository;
    private final AuditoriaDataHoraService relogio;
    public IntegracaoTransmissaoAuditoriaService(IntegracaoEventoRepository repository, AuditoriaDataHoraService relogio) {
        this.repository = repository; this.relogio = relogio;
    }
    public void iniciar(String etapa, String nfe, String cte, UUID ciclo, boolean consultaOrigem) {
        repository.registrar(new IntegracaoEventoRepository.Evento(UUID.randomUUID(), ciclo, null, "VEDACIT", etapa,
                IntegracaoEventoRepository.documento("VEDACIT", etapa, nfe, cte, null, null),
                consultaOrigem ? "CONSULTA_ORIGEM" : "ENVIO_INICIADO", "AGUARDANDO_CONFIRMACAO",
                consultaOrigem ? "CONSULTA_XML" : "TRANSMISSAO_INICIADA", relogio.agora(), null));
    }
    public boolean acessoXmlEmEspera() { return repository.acessoXmlEmEspera(relogio.agora()); }
}
