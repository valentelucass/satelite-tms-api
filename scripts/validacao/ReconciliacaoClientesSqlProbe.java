import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.satelite.repositories.ReconciliacaoLocalRepository;
import com.example.satelite.policy.ReconciliacaoLocalPolicy;
import tools.jackson.databind.ObjectMapper;

/** Só auditoria técnica nova, sempre com rollback. Nenhum cliente de integração é inicializado. */
public final class ReconciliacaoClientesSqlProbe {
    static void exigir(boolean condicao,String motivo) { if(!condicao) throw new IllegalStateException(motivo); }
    public static void main(String[] args) throws Exception {
        System.setProperty("logback.configurationFile",args[1]);
        Map<String,Object> resultado=new LinkedHashMap<>();
        try {
            Map<String,String> env=new HashMap<>();
            for(String linha:Files.readAllLines(Path.of(args[0]))) {
                int igual=linha.indexOf('='); if(igual<1 || linha.stripLeading().startsWith("#")) continue;
                String valor=linha.substring(igual+1).trim();
                if(valor.length()>1 && ((valor.startsWith("\"")&&valor.endsWith("\"")) || (valor.startsWith("'")&&valor.endsWith("'"))))
                    valor=valor.substring(1,valor.length()-1);
                env.put(linha.substring(0,igual).trim(),valor);
            }
            String url=env.getOrDefault("SATELITE_DB_URL",env.get("DB_URL"));
            exigir(url!=null && url.matches("(?is)^jdbc:sqlserver://[^;]+;.*databaseName=SATELITE_TMS_AUDITORIA(?:;.*)?$"),"DATABASE_OUT_OF_SCOPE");
            Properties props=new Properties();
            props.setProperty("user",env.getOrDefault("SATELITE_DB_USER",env.get("DB_USER")));
            props.setProperty("password",env.getOrDefault("SATELITE_DB_PASSWORD",env.get("DB_PASSWORD")));
            props.setProperty("loginTimeout","10");
            try(Connection c=DriverManager.getConnection(url,props)) {
                exigir("SATELITE_TMS_AUDITORIA".equalsIgnoreCase(c.getCatalog()),"DATABASE_OUT_OF_SCOPE");
                var ds=new SingleConnectionDataSource(c,true);
                var jdbc=new NamedParameterJdbcTemplate(ds);
                var manager=new DataSourceTransactionManager(ds);
                var repo=new ReconciliacaoLocalRepository(jdbc,manager);
                new TransactionTemplate(manager).executeWithoutResult(tx -> {
                    tx.setRollbackOnly(); repo.validarEstrutura();
                    LocalDate dia=LocalDate.of(2099,1,1); LocalDateTime agora=dia.atTime(2,0);
                    for(String cliente:List.of("PPG","SELIA","SUPPORTE")) {
                        var e=repo.abrirOuRetomar(cliente,dia,agora).orElseThrow();
                        exigir(e.dia().equals(dia),"EXECUCAO_REAL_ABERTA");
                        exigir(e.cliente().equals(cliente),"CLIENTE_DIVERGENTE");
                        exigir(repo.abrirOuRetomar(cliente,dia.plusDays(1),agora.plusDays(1)).orElseThrow().id()==e.id(),"RETOMADA_PERDIDA");
                        Set<Long> vistos=new HashSet<>(); int paginas=0;
                        while(true) {
                            var pagina=repo.pagina(e,7);
                            exigir(pagina.size()<=7,"PAGINA_SEM_LIMITE");
                            if(pagina.isEmpty()) break;
                            long anterior=e.ultimoId();
                            for(var item:pagina) {
                                exigir(item.id()>anterior && item.id()<=e.limiteId() && vistos.add(item.id()),"PAGINACAO_INCONSISTENTE");
                                String destino=jdbc.queryForObject("SELECT sistema_destino FROM dbo.tb_log_integracao WHERE id=:id",Map.of("id",item.id()),String.class);
                                exigir(cliente.equals(destino),"VAZAMENTO_ENTRE_CLIENTES");
                                var r=ReconciliacaoLocalPolicy.revisar(item);
                                repo.registrar(e,item,r,agora,agora.plusDays(1));
                                repo.registrar(e,item,r,agora,agora.plusDays(1)); // Idempotência.
                                anterior=item.id();
                            }
                            e=repo.abrirOuRetomar(cliente,dia,agora).orElseThrow();
                            exigir(e.ultimoId()==anterior,"CHECKPOINT_NAO_PERSISTIU");
                            exigir(++paginas<1000,"PAGINACAO_INFINITA");
                        }
                        long total=repo.resumo(cliente,e.id()).stream().mapToLong(r -> ((Number)r.get("quantidade")).longValue()).sum();
                        exigir(total==vistos.size(),"RESUMO_OU_IDEMPOTENCIA_DIVERGENTE");
                        String outro=cliente.equals("PPG") ? "SELIA" : "PPG";
                        exigir(repo.resumo(outro,e.id()).isEmpty(),"RESUMO_EXPOS_OUTRO_CLIENTE");
                        repo.concluir(e,agora.plusHours(1));
                        exigir(repo.abrirOuRetomar(cliente,dia,agora).isEmpty(),"EXECUCAO_CONCLUIDA_REABERTA");
                        exigir(!repo.execucoes(cliente).isEmpty(),"HISTORICO_AUSENTE");
                        resultado.put(cliente,Map.of("revisados",total,"paginas",paginas));
                    }
                });
                Long restantes=jdbc.queryForObject("SELECT COUNT_BIG(*) FROM dbo.tb_reconciliacao_cliente_execucao WHERE dia_referencia='2099-01-01'",Map.of(),Long.class);
                exigir(restantes!=null && restantes==0,"ROLLBACK_NAO_CONFIRMADO");
                resultado.put("rollback",true); resultado.put("envios",0); resultado.put("status","PASS");
            }
        } catch(Exception ex) {
            resultado.put("status","FAIL"); Throwable causa=ex; while(causa.getCause()!=null) causa=causa.getCause();
            resultado.put("tipo",causa.getClass().getSimpleName());
            if(causa instanceof SQLException s) resultado.put("sql_error_number",s.getErrorCode());
            else if(causa.getMessage()!=null && causa.getMessage().matches("[A-Z0-9_]+")) resultado.put("motivo",causa.getMessage());
        }
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(resultado));
        if(!"PASS".equals(resultado.get("status"))) System.exit(1);
    }
}
