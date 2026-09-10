import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;
import com.example.satelite.services.etl.EtlRepescagemService;
import tools.jackson.databind.ObjectMapper;

/** Valida somente SELECTs reais e o particionamento; nunca inicializa Spring ou integrações. */
public final class SqlSftpReadOnlyProbe {
    static int queries, maxParameters;
    static final String DB = "SATELITE_TMS_AUDITORIA";
    static final Map<String,Object> result = new LinkedHashMap<>();
    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    static void checkSql(String sql) {
        String clean = sql.replaceAll("(?s)/\\*.*?\\*/", "").strip().toLowerCase(Locale.ROOT);
        require(clean.startsWith("select ") || clean.startsWith("with ") || clean.equals("set lock_timeout 1500"), "NON_READ_SQL_BLOCKED");
        require(!clean.matches("(?s).*\\b(insert|update|delete|merge|truncate|drop|alter|create|execute|exec|into)\\b.*"), "WRITE_SQL_BLOCKED");
        int parameters=(int) sql.chars().filter(c -> c=='?').count();
        maxParameters=Math.max(maxParameters, parameters);
        require(parameters<=502, "SQL_PARAMETER_BOUND_EXCEEDED");
    }
    static Connection guard(Connection raw) {
        return (Connection) Proxy.newProxyInstance(SqlSftpReadOnlyProbe.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
            String name=method.getName();
            require(!name.equals("prepareCall") && !name.equals("commit"), "SQL_MUTATION_PATH_BLOCKED");
            if(name.equals("prepareStatement")) checkSql((String)args[0]);
            try {
                Object value=method.invoke(raw,args);
                if(value instanceof Statement statement) {
                    Class<?> type=value instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(SqlSftpReadOnlyProbe.class.getClassLoader(), new Class[]{type}, (p,m,a)->{
                        String operation=m.getName();
                        require(!operation.equals("executeUpdate")&&!operation.equals("executeLargeUpdate")&&!operation.equals("addBatch")&&!operation.equals("executeBatch"),"SQL_WRITE_METHOD_BLOCKED");
                        if(operation.equals("execute")||operation.equals("executeQuery")) {
                            if(a!=null && a.length>0 && a[0] instanceof String text) checkSql(text);
                            queries++;
                        }
                        try {return m.invoke(statement,a);} catch(InvocationTargetException e){throw e.getCause();}
                    });
                }
                return value;
            } catch(InvocationTargetException e) {throw e.getCause();}
        });
    }
    static String query(String name, Class<?>... parameters) throws Exception {
        return LogIntegracaoRepository.class.getMethod(name, parameters).getAnnotation(Query.class).value();
    }
    static List<Long> ids(List<LogIntegracaoModel> rows) {return rows.stream().map(LogIntegracaoModel::getId).toList();}
    static String jsonKeys(List<String> keys) throws Exception {return new ObjectMapper().writeValueAsString(keys);}
    static List<Long> baseline(Connection c, List<String> keys, boolean technical) throws Exception {
        String sql="WITH candidatos AS (SELECT id, data_processamento, chave_nfe, ROW_NUMBER() OVER (PARTITION BY chave_nfe ORDER BY data_processamento,id) AS pos FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT' AND sftp_cliente=? AND (arquivado=0 OR arquivado IS NULL) AND status_dados='SUCESSO' AND status_canhoto=? AND canhoto_classificacao_operacional=? AND chave_cte IS NOT NULL AND LTRIM(RTRIM(chave_cte))<>'' AND chave_nfe IN (SELECT value FROM OPENJSON(?))) SELECT TOP (10) id FROM candidatos WHERE pos=1 ORDER BY data_processamento,id";
        try(PreparedStatement p=c.prepareStatement(sql)) {
            p.setQueryTimeout(10);p.setString(1,"VEDACIT");p.setString(2,technical?"ERRO_DESTINO":"PENDENTE_FOTO");p.setString(3,technical?"PENDENTE_TECNICO":"PENDENTE_ENVIO");p.setString(4,jsonKeys(keys));
            try(ResultSet rs=p.executeQuery()){List<Long> out=new ArrayList<>();while(rs.next())out.add(rs.getLong(1));return out;}
        }
    }
    static long baselineCount(Connection c,List<String> keys) throws Exception {
        String sql="SELECT COUNT(DISTINCT chave_nfe) FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT' AND sftp_cliente=? AND (arquivado=0 OR arquivado IS NULL) AND status_dados='SUCESSO' AND status_canhoto='PENDENTE_FOTO' AND canhoto_classificacao_operacional='PENDENTE_ENVIO' AND chave_cte IS NOT NULL AND LTRIM(RTRIM(chave_cte))<>'' AND chave_nfe IN (SELECT value FROM OPENJSON(?))";
        try(PreparedStatement p=c.prepareStatement(sql)){p.setQueryTimeout(10);p.setString(1,"VEDACIT");p.setString(2,jsonKeys(keys));try(ResultSet rs=p.executeQuery()){rs.next();return rs.getLong(1);}}
    }
    @SuppressWarnings("unchecked")
    static void validate(Connection c) throws Exception {
        boolean snapshot;
        try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT snapshot_isolation_state FROM sys.databases WHERE name=DB_NAME()")){require(rs.next(),"DATABASE_STATE_MISSING");snapshot=rs.getInt(1)==1;}
        // Snapshot é preferido; alternativa serializável curta com timeout de lock preserva o mesmo conjunto.
        c.setTransactionIsolation(snapshot ? com.microsoft.sqlserver.jdbc.SQLServerConnection.TRANSACTION_SNAPSHOT : Connection.TRANSACTION_SERIALIZABLE);
        c.setAutoCommit(false);
        try(Statement st=c.createStatement()){st.execute("SET LOCK_TIMEOUT 1500");}
        result.put("isolation", snapshot?"SNAPSHOT":"SERIALIZABLE_WITH_1500MS_LOCK_TIMEOUT");
        Configuration cfg=new Configuration().addAnnotatedClass(LogIntegracaoModel.class);
        cfg.setProperty("hibernate.dialect","org.hibernate.dialect.SQLServerDialect");
        cfg.setProperty("hibernate.boot.allow_jdbc_metadata_access","false");
        cfg.setProperty("hibernate.hbm2ddl.auto","none");
        cfg.setProperty("hibernate.show_sql","false");
        cfg.setStatementInspector((StatementInspector)sql->{checkSql(sql);return sql;});
        try(SessionFactory factory=cfg.buildSessionFactory();Session session=factory.withOptions().connection(c).openSession()) {
            session.setDefaultReadOnly(true);session.setHibernateFlushMode(org.hibernate.FlushMode.MANUAL);
            List<String> keys=new ArrayList<>();
            try(PreparedStatement p=c.prepareStatement("SELECT DISTINCT chave_nfe FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT' AND sftp_cliente=? AND (arquivado=0 OR arquivado IS NULL) AND chave_nfe IS NOT NULL ORDER BY chave_nfe")) {
                p.setQueryTimeout(10);p.setString(1,"VEDACIT");try(ResultSet rs=p.executeQuery()){while(rs.next())keys.add(rs.getString(1).trim());}
            }
            require(keys.size()>2100,"INSUFFICIENT_REAL_NFE_FOR_BOUNDARY");
            result.put("audited_distinct_nfe",keys.size());
            String confirmationQuery=query("findConfirmacaoVedacitAtivaPorPar",String.class,String.class,String.class,Pageable.class);
            String confirmationSql="SELECT TOP (1) id FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT' AND (arquivado=0 OR arquivado IS NULL) AND (sftp_cliente IS NULL OR sftp_cliente=?) AND chave_nfe=? AND chave_cte=? AND status_dados='SUCESSO' AND (data_processamento_dados IS NOT NULL OR status_canhoto='SUCESSO') ORDER BY CASE WHEN status_canhoto='SUCESSO' THEN 0 WHEN canhoto_classificacao_operacional IN ('TIMEOUT_AMBIGUO','BLOQUEADO_DESTINO') THEN 1 ELSE 2 END, data_processamento DESC,id DESC";
            try(PreparedStatement sample=c.prepareStatement("SELECT TOP (1) chave_nfe,chave_cte FROM dbo.tb_log_integracao WHERE sistema_destino='VEDACIT' AND (arquivado=0 OR arquivado IS NULL) AND (sftp_cliente IS NULL OR sftp_cliente='VEDACIT') AND status_dados='SUCESSO' AND (data_processamento_dados IS NOT NULL OR status_canhoto='SUCESSO') AND chave_nfe IS NOT NULL AND chave_cte IS NOT NULL ORDER BY id DESC")) {
                sample.setQueryTimeout(10);
                String nfe,cte;
                try(ResultSet rs=sample.executeQuery()){require(rs.next(),"NO_CONFIRMATION_SAMPLE");nfe=rs.getString(1);cte=rs.getString(2);}
                List<LogIntegracaoModel> confirmation=session.createQuery(confirmationQuery,LogIntegracaoModel.class)
                        .setParameter("cliente","VEDACIT").setParameter("chaveNfe",nfe).setParameter("chaveCte",cte)
                        .setMaxResults(1).setReadOnly(true).setTimeout(10).getResultList();
                try(PreparedStatement baseline=c.prepareStatement(confirmationSql)) {
                    baseline.setQueryTimeout(10);baseline.setString(1,"VEDACIT");baseline.setString(2,nfe);baseline.setString(3,cte);
                    try(ResultSet rs=baseline.executeQuery()){require(rs.next()&&confirmation.size()==1&&confirmation.get(0).getId()==rs.getLong(1),"CONFIRMATION_QUERY_MISMATCH");}
                }
                result.put("active_confirmation_matches_independent_sql",true);
            }
            EtlRepescagemService service=new EtlRepescagemService(null,null,null,null,null);
            Method select=EtlRepescagemService.class.getDeclaredMethod("buscarRegistrosSftpEmLotes",List.class,int.class,Function.class);select.setAccessible(true);
            Method count=EtlRepescagemService.class.getDeclaredMethod("contarNfesSftpEmLotes",List.class,ToLongFunction.class);count.setAccessible(true);
            for(boolean technical:List.of(false,true)) {
                String jpql=query(technical?"findTecnicosSftpPorClienteENfes":"findCandidatosSftpPorClienteENfes",String.class,List.class,Pageable.class);
                int[] chunks={0};
                Function<List<String>,List<LogIntegracaoModel>> fetch=chunk->{
                    require(chunk.size()<=500,"CHUNK_TOO_LARGE");chunks[0]++;
                    return session.createQuery(jpql,LogIntegracaoModel.class).setParameter("cliente","VEDACIT").setParameterList("chavesNfe",chunk).setMaxResults(10).setReadOnly(true).setTimeout(10).getResultList();
                };
                List<LogIntegracaoModel> selected=(List<LogIntegracaoModel>)select.invoke(service,keys,10,fetch);
                List<Long> expected=baseline(c,keys,technical);
                require(ids(selected).equals(expected),"ORDER_OR_DEDUPLICATION_MISMATCH");
                String tag=technical?"technical":"normal";
                result.put(tag+"_queries",chunks[0]);result.put(tag+"_selected",selected.size());result.put(tag+"_matches_independent_sql",true);
            }
            String jpql=query("countNfesCandidatasSftpPorClienteENfes",String.class,List.class);
            ToLongFunction<List<String>> counter=chunk->session.createQuery(jpql,Long.class).setParameter("cliente","VEDACIT").setParameterList("chavesNfe",chunk).setTimeout(10).getSingleResult();
            long actual=(Long)count.invoke(service,keys,counter);long expected=baselineCount(c,keys);
            require(actual==expected,"DISTINCT_BALANCE_MISMATCH");result.put("candidate_balance",actual);result.put("balance_matches_independent_sql",true);
            // Repetição e entradas duplicadas não podem mudar seleção/contagem, sem efeitos de integração.
            List<String> repeated=new ArrayList<>(keys);repeated.addAll(keys.subList(0,Math.min(25,keys.size())));
            long duplicateCount=(Long)count.invoke(service,repeated,counter);require(duplicateCount==expected,"INPUT_DEDUPLICATION_MISMATCH");
            result.put("duplicate_input_count_stable",true);
        } finally {c.rollback();result.put("transaction_rolled_back",true);}
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("logback.configurationFile",args[1]);
        try {
            Map<String,String> env=new HashMap<>();
            for(String line:Files.readAllLines(Path.of(args[0]))){int eq=line.indexOf('=');if(eq<1||line.stripLeading().startsWith("#"))continue;String value=line.substring(eq+1).trim();if(value.length()>1&&((value.startsWith("\"")&&value.endsWith("\""))||(value.startsWith("'")&&value.endsWith("'"))))value=value.substring(1,value.length()-1);env.put(line.substring(0,eq).trim(),value);}
            String url=env.getOrDefault("SATELITE_DB_URL",env.get("DB_URL"));
            require(url!=null&&url.matches("(?is)^jdbc:sqlserver://[^;]+;.*databaseName=SATELITE_TMS_AUDITORIA(?:;.*)?$"),"DATABASE_OUT_OF_SCOPE");
            Properties props=new Properties();props.setProperty("user",env.getOrDefault("SATELITE_DB_USER",env.get("DB_USER")));props.setProperty("password",env.getOrDefault("SATELITE_DB_PASSWORD",env.get("DB_PASSWORD")));props.setProperty("loginTimeout","10");props.setProperty("applicationName","Codex-ReadOnly-Sftp-Validation");
            try(Connection raw=DriverManager.getConnection(url,props)) {
                require(DB.equalsIgnoreCase(raw.getCatalog()),"DATABASE_OUT_OF_SCOPE");
                validate(guard(raw));
            }
            result.put("status","PASS");
        } catch(Exception e) {
            result.put("status","FAIL");Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();result.put("error_type",cause.getClass().getSimpleName());
            if(cause instanceof SQLException sql)result.put("sql_error_number",sql.getErrorCode());
            else if(cause instanceof IllegalStateException && cause.getMessage()!=null && cause.getMessage().matches("[A-Z0-9_]+"))result.put("reason",cause.getMessage());
        }
        result.put("executed_queries",queries);result.put("maximum_parameters",maxParameters);result.put("database",DB);
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        if(!"PASS".equals(result.get("status")))System.exit(1);
    }
}
