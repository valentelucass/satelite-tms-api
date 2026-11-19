package com.example.satelite.services.etl;

import static com.example.satelite.dto.auditoria.ReconciliacaoVedacitDTO.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReconciliacaoVedacitRelatorioService {
    private final Path pasta;
    public ReconciliacaoVedacitRelatorioService(
            @Value("${VEDACIT_RECONCILIACAO_REPORT_PATH:logs/reconciliacao-vedacit}") String pasta) {
        this.pasta=Path.of(pasta).toAbsolutePath().normalize();
    }
    public void escrever(Execucao e,List<Resumo> resumo,boolean completa,LocalDateTime agora) {
        StringBuilder texto=new StringBuilder("# Revisao noturna Vedacit — ").append(e.dia()).append("\n\n")
                .append("Atualizado em: ").append(agora).append(" (America/Sao_Paulo). Execucao: ").append(e.id()).append(".\n\n")
                .append(completa?"Varredura concluida. Os resultados abaixo distinguem revisao de resolucao.\n\n":
                        "Varredura em andamento; checkpoint salvo para retomada.\n\n")
                .append("| Etapa | Resultado | Registros |\n|---|---|---:|\n");
        for(Resumo r:resumo) texto.append("| ").append(r.etapa()).append(" | ").append(r.resultado()).append(" | ")
                .append(r.quantidade()).append(" |\n");
        texto.append("\nCada registro tem ultima e proxima revisao em tb_reconciliacao_vedacit_item. ")
                .append("As etapas podem corresponder ao mesmo documento; nao somar como documentos distintos.\n\n")
                .append("SEM_PERMISSAO: liberar consulta/download na fonte. AUSENTE: disponibilizar arquivo exato. ")
                .append("DATA_A_CONFERIR: recuperar primeira data com evidencia historica. ")
                .append("VINCULO_CTE_A_CONFERIR: a consulta por NF-e nao identifica o CT-e; envio continua protegido.\n");
        try {
            Files.createDirectories(pasta);
            Files.writeString(pasta.resolve(e.dia()+"-"+e.id()+".md"),texto,StandardCharsets.UTF_8);
        } catch(IOException ex) { throw new IllegalStateException("FALHA_RELATORIO_RECONCILIACAO",ex); }
    }
}
