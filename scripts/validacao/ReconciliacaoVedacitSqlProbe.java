import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;
import com.example.satelite.dto.auditoria.ReconciliacaoVedacitDTO.*;
import tools.jackson.databind.ObjectMapper;

/** Ensaia o repositorio real. Escreve apenas auditoria nova dentro de transacao obrigatoriamente revertida. */
public final class ReconciliacaoVedacitSqlProbe {
    static void exigir(boolean condicao,String motivo) { if(!condicao) throw new IllegalStateException(motivo); }
    public static void main(String[] args) throws Exception {
        System.setProperty("logback.configurationFile",args[1]);
        Map<String,Object> resultado=new LinkedHashMap<>();
        try {
            Map<String,String> env=new HashMap<>();
            for(String linha:Files.readAllLines(Path.of(args[0]))) {
                int igual=linha.indexOf('=');if(igual<1 || linha.stripLeading().startsWith("#"))continue;
                String valor=linha.substring(igual+1).trim();
                if(valor.length()>1 && ((valor.startsWith("\"")&&valor.endsWith("\"")) || (valor.startsWith("'")&&valor.endsWith("'"))))
                    valor=valor.substring(1,valor.length()-1);
                env.put(linha.substring(0,igual).trim(),valor);
            }
            String url=env.getOrDefault("SATELITE_DB_URL",env.get("DB_URL"));
            exigir(url!=null && url.matches("(?is)^jdbc:sqlserver://[^;]+;.*databaseName=SATELITE_TMS_AUDITORIA(?:;.*)?$"),"DATABASE_OUT_OF_SCOPE");
            Properties props=new Properties();props.setProperty("user",env.getOrDefault("SATELITE_DB_USER",env.get("DB_USER")));
            props.setProperty("password",env.getOrDefault("SATELITE_DB_PASSWORD",env.get("DB_PASSWORD")));
            props.setProperty("loginTimeout","10");props.setProperty("applicationName","Reconciliacao-Rollback-Probe");
            try(Connection connection=DriverManager.getConnection(url,props)) {
                exigir("SATELITE_TMS_AUDITORIA".equalsIgnoreCase(connection.getCatalog()),"DATABASE_OUT_OF_SCOPE");
                var ds=new SingleConnectionDataSource(connection,true);
                var jdbc=new NamedParameterJdbcTemplate(ds);
                var manager=new DataSourceTransactionManager(ds);
                var repo=new ReconciliacaoVedacitRepository(jdbc,manager);
                new TransactionTemplate(manager).executeWithoutResult(tx->{
                    tx.setRollbackOnly();
                    repo.validarEstrutura();
                    LocalDate dia=LocalDate.of(2099,1,1);LocalDateTime agora=dia.atTime(2,0);
                    Execucao e=repo.abrirOuRetomar(dia,agora).orElseThrow();
                    exigir(e.dia().equals(dia),"OUTRA_REVISAO_ABERTA_NAO_USAR_COMO_TESTE");
                    repo.bloquearFonte(e.id(),"CONSULTA_XML","CONSULTA_SEM_PERMISSAO");
                    exigir(repo.abrirOuRetomar(dia,agora).orElseThrow().bloqueioConsultaXml()!=null,"CIRCUITO_NAO_PERSISTIU");
                    e=repo.abrirOuRetomar(dia.plusDays(1),agora.plusDays(1)).orElseThrow();
                    exigir(e.bloqueioConsultaXml()==null,"CIRCUITO_NAO_REABRIU_NA_NOITE_SEGUINTE");
                    Set<Long> vistos=new HashSet<>();int paginas=0,arquivados=0,nuncaTentados=0;
                    while(true) {
                        List<Candidato> pagina=repo.pagina(e,200);
                        if(pagina.isEmpty()) break;
                        long anterior=e.ultimoId();
                        for(Candidato c:pagina) {
                            exigir(c.id()>anterior && c.id()<=e.limiteId(),"PAGINACAO_FORA_DO_SNAPSHOT");
                            exigir(vistos.add(c.id()),"PAGINACAO_REPETIU_ITEM");anterior=c.id();
                            if(c.arquivado())arquivados++;
                            if(!c.arquivado() && "PENDENTE_ORIGEM".equals(c.statusXml()) && c.dataXml()==null && c.tentativasXml()==0 && !c.xmlSucessoHistorico())nuncaTentados++;
                            repo.registrar(e,c,new Revisao("PROBE","PROBE","SEM_ENVIO","Ensaio com rollback"),agora,agora.plusDays(1));
                        }
                        paginas++;
                        e=repo.abrirOuRetomar(dia.plusDays(1),agora).orElseThrow();
                        exigir(e.ultimoId()==anterior,"CHECKPOINT_NAO_PERSISTIU");
                        exigir(paginas<1000,"PAGINACAO_NAO_TERMINA");
                    }
                    long id=e.id();
                    exigir(repo.resumo(id).stream().filter(r->r.etapa().equals("ACAO")).mapToLong(Resumo::quantidade).sum()==vistos.size(),"RESUMO_DIVERGE_DA_VARREDURA");
                    exigir(repo.itens(id,0,500).size()<=200,"PAGINA_PUBLICA_SEM_LIMITE");
                    repo.concluir(id,agora.plusHours(1));
                    exigir(repo.abrirOuRetomar(dia,agora).isEmpty(),"NOITE_CONCLUIDA_REABERTA");
                    resultado.put("paginas",paginas);resultado.put("registros_revisados",vistos.size());
                    resultado.put("arquivados_conferidos_sem_reativar",arquivados);resultado.put("xml_inventario_nunca_tentado",nuncaTentados);
                    resultado.put("checkpoint_retomada_paginacao_circuito_resumo",true);
                });
                Long restantes=jdbc.queryForObject("SELECT COUNT_BIG(*) FROM dbo.tb_reconciliacao_vedacit_execucao WHERE dia_referencia='2099-01-01'",Map.of(),Long.class);
                exigir(restantes!=null && restantes==0,"ROLLBACK_NAO_CONFIRMADO");
                resultado.put("rollback_confirmado",true);resultado.put("envios",0);resultado.put("status","PASS");
            }
        } catch(Exception ex) {
            resultado.put("status","FAIL");Throwable causa=ex;while(causa.getCause()!=null)causa=causa.getCause();
            resultado.put("tipo_erro",causa.getClass().getSimpleName());
            if(causa instanceof SQLException s) resultado.put("sql_error_number",s.getErrorCode());
            else if(causa.getMessage()!=null && causa.getMessage().matches("[A-Z0-9_]+"))resultado.put("motivo",causa.getMessage());
        }
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(resultado));
        if(!"PASS".equals(resultado.get("status")))System.exit(1);
    }
}
