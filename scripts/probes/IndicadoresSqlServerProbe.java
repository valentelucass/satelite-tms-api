import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import com.example.satelite.repositories.IntegracaoIndicadoresEtapasRepository;
import com.example.satelite.repositories.WorkSftpClientesAuditoriaRepository;
import com.example.satelite.dto.auditoria.IndicadoresEtapasDTO;

/** Executa somente SELECTs da implementação candidata contra o banco permitido. */
public class IndicadoresSqlServerProbe {
    public static void main(String[] args) throws Exception {
        var url=System.getenv("PROBE_DB_URL");
        if(url==null || !url.toLowerCase().contains("databasename=satelite_tms_auditoria")) throw new AssertionError("DATABASE_OUT_OF_SCOPE");
        var jdbc=new NamedParameterJdbcTemplate(new DriverManagerDataSource(url,System.getenv("PROBE_DB_USER"),System.getenv("PROBE_DB_PASSWORD")));
        new WorkSftpClientesAuditoriaRepository(jdbc).validarEstrutura();
        var repo=new IntegracaoIndicadoresEtapasRepository(jdbc);
        for(int i=0;i<10;i++) {
            var dia=repo.consultar(LocalDate.of(2026,9,11),LocalDate.of(2026,9,11),List.of("VEDACIT"));
            var pod=dia.etapas().stream().filter(e -> e.etapa().equals("COMPROVANTE")).findFirst().orElseThrow();
            if(pod.sucessosPeriodo()!=274 || pod.confirmadosSemDataConfiavel()!=702) throw new AssertionError("INCIDENTE_COUNTS_CHANGED");
            validar(dia);
        }
        var mes=repo.consultar(LocalDate.of(2026,9,1),LocalDate.of(2026,9,11),List.of("VEDACIT")); validar(mes);
        var outros=repo.consultar(LocalDate.of(2026,9,1),LocalDate.of(2026,9,11),List.of("PPG")); validar(outros);
        if(outros.etapas().stream().anyMatch(e -> e.sistemaDestino().equals("VEDACIT"))) throw new AssertionError("FILTER_LEAK");
        Files.writeString(Path.of(args[0]),json(mes));
        System.out.println("PROBE_PASS 10_readings_stable_today_274_uncertain_702_cards_equal_series_destinations_isolated_schema_ready");
    }
    private static void validar(IndicadoresEtapasDTO dto) {
        if(dto.versao()!=2) throw new AssertionError("CONTRACT_VERSION");
        for(var etapa:List.of("DADOS","COMPROVANTE")) {
            long cards=dto.etapas().stream().filter(e -> etapa.equals(e.etapa())).mapToLong(e -> e.sucessosPeriodo()).sum();
            long grafico=dto.evolucao().stream().filter(e -> etapa.equals(e.etapa())).mapToLong(e -> e.sucessos()).sum();
            if(cards!=grafico) throw new AssertionError("CARD_SERIES_MISMATCH");
        }
    }
    private static String json(IndicadoresEtapasDTO d) {
        String etapas=d.etapas().stream().map(e -> String.format("{\"sistemaDestino\":\"%s\",\"etapa\":\"%s\",\"sucessosPeriodo\":%d,\"falhasPeriodo\":%d,\"pendentesAtuais\":%d,\"bloqueadosAtuais\":%d,\"semConfirmacaoDatada\":%d,\"confirmadosSemDataConfiavel\":%d}",e.sistemaDestino(),e.etapa(),e.sucessosPeriodo(),e.falhasPeriodo(),e.pendentesAtuais(),e.bloqueadosAtuais(),e.semConfirmacaoDatada(),e.confirmadosSemDataConfiavel())).collect(Collectors.joining(","));
        String dias=d.evolucao().stream().map(e -> String.format("{\"data\":\"%s\",\"etapa\":\"%s\",\"sucessos\":%d,\"falhas\":%d}",e.data(),e.etapa(),e.sucessos(),e.falhas())).collect(Collectors.joining(","));
        return String.format("{\"versao\":2,\"dataInicial\":\"%s\",\"dataFinal\":\"%s\",\"etapas\":[%s],\"evolucao\":[%s]}",d.dataInicial(),d.dataFinal(),etapas,dias);
    }
}
