import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.example.satelite.repositories.*;
import tools.jackson.databind.ObjectMapper;

/** Repositórios reais no SQL Server, apenas leitura, sem inicializar Spring ou integrações. */
public final class SqlIndicadoresOperacionaisReadOnlyProbe {
    public static void main(String[] args) throws Exception {
        var result = new LinkedHashMap<String,Object>();
        try {
            var env = new HashMap<String,String>();
            for (String line : Files.readAllLines(Path.of(args[0]))) {
                int eq=line.indexOf('='); if(eq<1 || line.stripLeading().startsWith("#")) continue;
                String value=line.substring(eq+1).trim();
                if(value.length()>1 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))))
                    value=value.substring(1,value.length()-1);
                env.put(line.substring(0,eq).trim(),value);
            }
            String url=env.getOrDefault("SATELITE_DB_URL",env.get("DB_URL"));
            SqlSftpReadOnlyProbe.require(url!=null && url.matches("(?is)^jdbc:sqlserver://[^;]+;.*databaseName=SATELITE_TMS_AUDITORIA(?:;.*)?$"),"DATABASE_OUT_OF_SCOPE");
            var props=new Properties();
            props.setProperty("user",env.getOrDefault("SATELITE_DB_USER",env.get("DB_USER")));
            props.setProperty("password",env.getOrDefault("SATELITE_DB_PASSWORD",env.get("DB_PASSWORD")));
            props.setProperty("loginTimeout","10");
            try(var raw=DriverManager.getConnection(url,props)) {
                SqlSftpReadOnlyProbe.require("SATELITE_TMS_AUDITORIA".equalsIgnoreCase(raw.getCatalog()),"DATABASE_OUT_OF_SCOPE");
                raw.setAutoCommit(false);
                var connection=SqlSftpReadOnlyProbe.guard(raw);
                try {
                    try(var st=connection.createStatement()){st.execute("SET LOCK_TIMEOUT 1500");}
                    var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true)); jdbc.setQueryTimeout(15);
                    var named=new NamedParameterJdbcTemplate(jdbc);
                    var repo=new IntegracaoOperacionalRepository(named);
                    var inicio=LocalDate.parse(args[1]);var fim=LocalDate.parse(args[2]);
                    var destinos=List.of("VEDACIT","SELIA","PPG");
                    var antigo=new IntegracaoIndicadoresEtapasRepository(named).consultar(inicio,fim,destinos);
                    var dto=repo.consultar(inicio,fim,destinos);
                    result.put("indicadores",dto);
                    int categorias=0;
                    for(var etapa:dto.etapas()) {
                        var anterior=antigo.etapas().stream().filter(e->e.sistemaDestino().equals(etapa.sistemaDestino()) && e.etapa().equals(etapa.etapa())).findFirst().orElseThrow();
                        SqlSftpReadOnlyProbe.require(etapa.sucessosPeriodo()==anterior.sucessosPeriodo(),"CONFIRMATIONS_CHANGED");
                        SqlSftpReadOnlyProbe.require(etapa.confirmadosSemDataConfiavel()==anterior.confirmadosSemDataConfiavel(),"UNCERTAIN_DATE_CHANGED");
                        var contagens=Map.of("AGUARDANDO_PROCESSAMENTO",etapa.aguardandoProcessamento(),"NOVA_TENTATIVA",etapa.novaTentativa(),
                                "AGUARDANDO_ORIGEM",etapa.aguardandoOrigem(),"AGUARDANDO_CONFIRMACAO",etapa.aguardandoConfirmacao(),
                                "INTERVENCAO",etapa.intervencao(),"A_CONFERIR",etapa.semConfirmacaoDatada());
                        for(var acao:contagens.entrySet()) {
                            var page=repo.pendencias(List.of(etapa.sistemaDestino()),etapa.etapa(),acao.getKey(),0,25);
                            SqlSftpReadOnlyProbe.require(page.totalElementos()==acao.getValue(),"DETAIL_TOTAL_MISMATCH");
                            SqlSftpReadOnlyProbe.require(page.itens().size()==Math.min(25,acao.getValue()),"DETAIL_PAGE_MISMATCH");
                            categorias++;
                        }
                    }
                    for(String etapa:List.of("DADOS","COMPROVANTE")) {
                        long total=dto.etapas().stream().filter(e->e.etapa().equals(etapa)).mapToLong(e->e.sucessosPeriodo()).sum();
                        long dias=dto.evolucao().stream().filter(e->e.etapa().equals(etapa)).mapToLong(e->e.sucessos()).sum();
                        SqlSftpReadOnlyProbe.require(total==dias,"DAILY_TOTAL_MISMATCH");
                    }
                    var ciclos=new WorkSftpClientesAuditoriaRepository(named);
                    result.put("ultimos_ciclos",ciclos.buscarUltimosCiclos());
                    var pendentes=ciclos.buscarHistorico("VEDACIT","COM_PENDENCIAS",inicio.atStartOfDay(),fim.plusDays(1).atStartOfDay(),0,10,"SFTP");
                    SqlSftpReadOnlyProbe.require(pendentes.itens().stream().allMatch(e->"COM_PENDENCIAS".equals(e.resultadoExecucao())),"CYCLE_FILTER_MISMATCH");
                    result.put("ciclos_com_pendencias",pendentes.totalElementos());
                    result.put("categorias_detalhamento_conferidas",categorias);
                    result.put("acesso_xml_em_espera",new IntegracaoEventoRepository(named).acessoXmlEmEspera(LocalDateTime.now()));
                    result.put("confirmacoes_v2_preservadas",true);
                } finally { connection.rollback(); result.put("rollback",true); }
            }
            result.put("status","PASS");
        } catch(Exception e) {
            Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
            result.put("status","FAIL"); result.put("error_type",cause.getClass().getSimpleName());
            if(cause instanceof SQLException sql) result.put("sql_error_number",sql.getErrorCode());
            else if(cause.getMessage()!=null && cause.getMessage().matches("[A-Z0-9_]+"))result.put("reason",cause.getMessage());
        }
        result.put("database","SATELITE_TMS_AUDITORIA");result.put("executed_queries",SqlSftpReadOnlyProbe.queries);
        Files.writeString(Path.of(args[3]),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println(result.get("status"));if(!"PASS".equals(result.get("status")))System.exit(1);
    }
}

