import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.example.satelite.services.etl.SftpDocumentoLockService;

/** Duas instâncias, oito threads e sessões SQL independentes, sem chamar destinos. */
public class DocumentoLockSqlServerProbe {
    public static void main(String[] args) throws Exception {
        var url=System.getenv("PROBE_DB_URL");
        if(url==null || !url.toLowerCase().contains("databasename=satelite_tms_auditoria")) throw new AssertionError("DATABASE_OUT_OF_SCOPE");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("PROBE_DB_USER"),System.getenv("PROBE_DB_PASSWORD")));
        var a=new SftpDocumentoLockService(jdbc); var b=new SftpDocumentoLockService(jdbc);
        var ativos=new AtomicInteger(); var max=new AtomicInteger(); var aceito=new AtomicBoolean(); var envios=new AtomicInteger();
        var executor=Executors.newFixedThreadPool(8);
        try {
            var tarefas=new ArrayList<Callable<Boolean>>();
            for(int i=0;i<100;i++) {
                final var service=i%2==0?a:b; final var cliente=i%2==0?"CLIENTE_A":"CLIENTE_B";
                tarefas.add(() -> service.executarComLock(cliente,"8".repeat(44),"9".repeat(44),() -> {
                    max.accumulateAndGet(ativos.incrementAndGet(),Math::max);
                    try {
                        if(!aceito.get()) { envios.incrementAndGet(); aceito.set(true); }
                        return service.executarComLock("OUTRO_PERFIL","8".repeat(44),"9".repeat(44),() -> true).orElseThrow();
                    } finally { ativos.decrementAndGet(); }
                }).orElse(false));
            }
            for(var f:executor.invokeAll(tarefas,45,TimeUnit.SECONDS)) if(!f.get()) throw new AssertionError("LOCK_TIMEOUT");
            if(max.get()!=1 || envios.get()!=1) throw new AssertionError("DUPLICATE_CRITICAL_SECTION");
            System.out.println("PROBE_PASS sqlserver_100_disputes_8_threads_2_instances_nested_aliases_max_active_1_effect_1");
        } finally { executor.shutdownNow(); }
    }
}
