import java.time.LocalDateTime;
import java.util.Map;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import com.example.satelite.models.LogIntegracaoModel;

/** Validação real JDBC/Hibernate: apenas registros sintéticos em transação revertida. */
public class ConfirmacaoSqlServerProbe {
    public static void main(String[] args) {
        var url=System.getenv("PROBE_DB_URL");
        if(url==null || !url.toLowerCase().contains("databasename=satelite_tms_auditoria"))
            throw new IllegalStateException("DATABASE_OUT_OF_SCOPE");
        var registry=new StandardServiceRegistryBuilder().applySettings(Map.of(
                "hibernate.connection.url",url,"hibernate.connection.username",System.getenv("PROBE_DB_USER"),
                "hibernate.connection.password",System.getenv("PROBE_DB_PASSWORD"),
                "hibernate.hbm2ddl.auto","none","hibernate.show_sql","false")).build();
        try(var factory=new MetadataSources(registry).addAnnotatedClass(LogIntegracaoModel.class).buildMetadata().buildSessionFactory();
            var session=factory.openSession()) {
            var tx=session.beginTransaction();
            try {
                var nfe="8".repeat(44); var cte="9".repeat(44);
                long existentes=((Number)session.createNativeQuery("SELECT COUNT(*) FROM dbo.tb_log_integracao WHERE chave_nfe=:nf",Long.class)
                        .setParameter("nf",nfe).getSingleResult()).longValue();
                if(existentes!=0) throw new AssertionError("SYNTHETIC_KEY_EXISTS");
                var antiga=LocalDateTime.of(2026,8,20,12,0);
                var log=LogIntegracaoModel.builder().sistemaDestino("VEDACIT").status("ENVIADO")
                        .statusDados("SUCESSO").statusCanhoto("SUCESSO").chaveNfe(nfe).chaveCte(cte)
                        .dataProcessamentoCanhoto(antiga).dataProcessamento(antiga)
                        .tentativasDados(1).tentativasCanhoto(1).arquivado(false).build();
                session.persist(log); session.flush();
                if(log.getId()==null) throw new AssertionError("GENERATED_ID_MISSING");
                log.setDataProcessamentoDados(LocalDateTime.of(2026,9,11,12,0)); session.flush();
                var primeira=session.createNativeQuery("SELECT primeira_confirmacao_em FROM dbo.tb_confirmacao_comprovante WHERE chave_nfe=:nf AND chave_cte=:ct",LocalDateTime.class)
                        .setParameter("nf",nfe).setParameter("ct",cte).getSingleResult();
                if(!antiga.equals(primeira)) throw new AssertionError("FIRST_DATE_CHANGED");
                System.out.println("PROBE_PASS hibernate_identity_insert_and_xml_update_atomic_confirmation");
            } finally { tx.rollback(); }
            var restantes=session.createNativeQuery("SELECT COUNT(*) FROM dbo.tb_log_integracao WHERE chave_nfe=:nf",Long.class)
                    .setParameter("nf","8".repeat(44)).getSingleResult();
            if(restantes!=0) throw new AssertionError("ROLLBACK_FAILED");
            System.out.println("PROBE_PASS rollback_no_audit_rows_left");
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }
}
