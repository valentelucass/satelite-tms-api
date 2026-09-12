package com.example.satelite.repositories;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.example.satelite.dto.auditoria.IndicadoresEtapasDTO;

class IntegracaoIndicadoresEtapasRepositoryTest {
    private JdbcTemplate jdbc;
    private IntegracaoIndicadoresEtapasRepository repository;

    @BeforeEach
    void prepararBancoEmMemoria() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:etapas_" + UUID.randomUUID()
                + ";MODE=MSSQLServer;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA dbo");
        jdbc.execute("CREATE TABLE dbo.tb_confirmacao_comprovante (chave_nfe VARCHAR(44), chave_cte VARCHAR(44), primeira_confirmacao_em TIMESTAMP, PRIMARY KEY(chave_nfe,chave_cte))");
        jdbc.execute("""
                CREATE TABLE dbo.tb_log_integracao (
                    id BIGINT PRIMARY KEY, sistema_destino VARCHAR(20), occurrence_id BIGINT,
                    chave_cte VARCHAR(44), chave_nfe VARCHAR(44), canhoto_chave_cte_efetiva VARCHAR(44),
                    data_processamento TIMESTAMP, status_dados VARCHAR(50), status_canhoto VARCHAR(50),
                    data_processamento_dados TIMESTAMP, data_processamento_canhoto TIMESTAMP,
                    canhoto_classificacao_operacional VARCHAR(40), arquivado INT)
                """);
        repository = new IntegracaoIndicadoresEtapasRepository(new NamedParameterJdbcTemplate(ds));
    }

    private void log(long id, String cte, String nfe, String dados, String comprovante,
            String dataDados, String dataComprovante, String bloqueio) {
        jdbc.update("""
                INSERT INTO dbo.tb_log_integracao (id, sistema_destino, occurrence_id, chave_cte, chave_nfe,
                    data_processamento, status_dados, status_canhoto, data_processamento_dados,
                    data_processamento_canhoto, canhoto_classificacao_operacional, arquivado)
                VALUES (?, 'VEDACIT', ?, ?, ?, '2026-09-10 12:00:00', ?, ?, ?, ?, ?, 0)
                """, id, id, cte, nfe, dados, comprovante, dataDados, dataComprovante, bloqueio);
    }

    private IndicadoresEtapasDTO consultar() {
        return repository.consultar(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-10"), List.of("VEDACIT"));
    }

    @Test
    void aceiteDuravelNaoViraHojeNemPendenteAposSobrescritaDoLog() {
        log(1, "cte1", "nf1", "SUCESSO", "PENDENTE_FOTO", null, "2026-09-09 12:00:00", "PENDENTE_ENVIO");
        jdbc.update("INSERT INTO dbo.tb_confirmacao_comprovante VALUES ('nf1','cte1','2026-08-20 12:00:00')");
        var dto = consultar();
        assertEquals(2, dto.versao());
        assertEquals(0, etapa(dto, "COMPROVANTE").sucessosPeriodo());
        assertEquals(0, etapa(dto, "COMPROVANTE").pendentesAtuais());
        assertTrue(dto.evolucao().isEmpty());
    }

    @Test
    void dataAfetadaPeloIncidenteNaoEntraNoDiaNemNaFilaDePendencias() {
        log(1, "cte1", "nf1", "SUCESSO", "SUCESSO", null, "2026-09-09 12:00:00", "SUCESSO");
        jdbc.update("INSERT INTO dbo.tb_confirmacao_comprovante VALUES ('nf1','cte1',NULL)");
        var pod = etapa(consultar(), "COMPROVANTE");
        assertEquals(0, pod.sucessosPeriodo());
        assertEquals(1, pod.confirmadosSemDataConfiavel());
        assertEquals(0, pod.pendentesAtuais());
        assertEquals(0, pod.semConfirmacaoDatada());
        assertTrue(consultar().evolucao().isEmpty());
    }

    @Test
    void ledgerUsaCteEfetivoSemOcultarOutroComprovanteDaMesmaNota() {
        log(1, "cteOriginal", "nf1", "SUCESSO", "SUCESSO", null, "2026-09-09 12:00:00", "SUCESSO");
        jdbc.update("UPDATE dbo.tb_log_integracao SET canhoto_chave_cte_efetiva='cteEfetivo'");
        jdbc.update("INSERT INTO dbo.tb_confirmacao_comprovante VALUES ('nf1','cteEfetivo','2026-08-20 12:00:00')");
        log(2, "cteOutro", "nf1", "SUCESSO", "SUCESSO", null, "2026-09-09 12:00:00", "SUCESSO");
        assertEquals(1, etapa(consultar(), "COMPROVANTE").sucessosPeriodo());
    }

    private IndicadoresEtapasDTO.Etapa etapa(IndicadoresEtapasDTO dto, String etapa) {
        return dto.etapas().stream().filter(e -> e.etapa().equals(etapa)).findFirst().orElseThrow();
    }

    @Test
    void xmlAntigoNaoViraEnvioNovoQuandoComprovanteAtualiza() {
        log(1, "cte1", "nf1", "SUCESSO", "SUCESSO", "2026-08-20 14:28:00", "2026-09-07 13:00:00", "SUCESSO");
        var dto = consultar();
        assertEquals(0, etapa(dto, "DADOS").sucessosPeriodo());
        assertEquals(1, etapa(dto, "COMPROVANTE").sucessosPeriodo());
        assertEquals(1, dto.evolucao().size());
        assertEquals("COMPROVANTE", dto.evolucao().get(0).etapa());
    }

    @Test
    void sucessoSemDataNaoContaComoXmlConfirmado() {
        log(1, "cte1", "nf1", "SUCESSO", "SUCESSO", null, "2026-09-07 13:00:00", "SUCESSO");
        var dto = consultar();
        assertEquals(0, etapa(dto, "DADOS").sucessosPeriodo());
        assertEquals(1, etapa(dto, "DADOS").semConfirmacaoDatada());
        assertEquals(1, etapa(dto, "COMPROVANTE").sucessosPeriodo());
    }

    @Test
    void bloqueiosNaoSomemDoSaldoNemViraramEnvios() {
        log(1, "cte1", "nf1", "PENDENTE_ORIGEM", "PENDENTE_FOTO", null, null, "BLOQUEADO_ORIGEM");
        var dto = consultar();
        assertEquals(1, etapa(dto, "DADOS").bloqueadosAtuais());
        assertEquals(1, etapa(dto, "COMPROVANTE").bloqueadosAtuais());
        assertEquals(0, etapa(dto, "COMPROVANTE").pendentesAtuais());
        assertTrue(dto.evolucao().isEmpty());
    }

    @Test
    void saldoAtualIncluiPendenciasAnterioresAoPeriodo() {
        log(1, "cte1", "nf1", "SUCESSO", "PENDENTE_FOTO", "2026-08-01 12:00:00", null, "PENDENTE_ENVIO");
        jdbc.update("UPDATE dbo.tb_log_integracao SET data_processamento = '2026-08-01 12:00:00'");
        assertEquals(1, etapa(consultar(), "COMPROVANTE").pendentesAtuais());
    }

    @Test
    void timeoutDeComprovanteNaoEHerdadoComoSucessoXml() {
        log(1, "cte1", "nf1", "SUCESSO", "ERRO_DESTINO", "2026-08-20 12:00:00", "2026-09-08 12:00:00", "TIMEOUT_AMBIGUO");
        var dto = consultar();
        assertEquals(0, etapa(dto, "DADOS").falhasPeriodo());
        assertEquals(1, etapa(dto, "COMPROVANTE").falhasPeriodo());
        assertEquals(1, etapa(dto, "COMPROVANTE").bloqueadosAtuais());
        assertEquals(0, dto.evolucao().get(0).sucessos());
        assertEquals(1, dto.evolucao().get(0).falhas());
    }

    @Test
    void umXmlParaDuasNotasMantemDoisComprovantes() {
        log(1, "cte1", "nf1", "SUCESSO", "SUCESSO", "2026-09-01 12:00:00", "2026-09-02 12:00:00", "SUCESSO");
        log(2, "cte1", "nf2", "SUCESSO", "SUCESSO", "2026-09-01 12:00:00", "2026-09-03 12:00:00", "SUCESSO");
        assertEquals(1, etapa(consultar(), "DADOS").sucessosPeriodo());
        assertEquals(2, etapa(consultar(), "COMPROVANTE").sucessosPeriodo());
    }

    @Test
    void reconciliacaoNaoMudaDataDaConfirmacaoOriginal() {
        log(1, "cte1", "nf1", "SUCESSO", "SUCESSO", "2026-08-20 12:00:00", "2026-08-21 12:00:00", "SUCESSO");
        log(2, "cte1", "nf1", "SUCESSO", "SUCESSO", "2026-09-07 12:00:00", "2026-09-07 12:00:00", "SUCESSO");
        assertTrue(consultar().evolucao().isEmpty());
    }

    @Test
    void confirmacaoDatadaPrevaleceSobreCopiaPendente() {
        log(1, "cte1", "nf1", "SUCESSO", "SUCESSO", "2026-09-01 12:00:00", "2026-09-02 12:00:00", "SUCESSO");
        log(2, "cte1", "nf1", "PENDENTE_ORIGEM", "PENDENTE_FOTO", null, null, "BLOQUEADO_ORIGEM");
        var dto = consultar();
        assertEquals(1, etapa(dto, "DADOS").sucessosPeriodo());
        assertEquals(0, etapa(dto, "DADOS").bloqueadosAtuais());
        assertEquals(1, etapa(dto, "COMPROVANTE").sucessosPeriodo());
    }

    @Test
    void naoAplicavelIgnoradoEArquivadoNaoContamComoEnvios() {
        log(1, "cte1", "nf1", "IGNORADO", "NAO_APLICAVEL", "2026-09-07 12:00:00", "2026-09-07 12:00:00", null);
        log(2, "cte2", "nf2", "SUCESSO", "SUCESSO", "2026-09-07 12:00:00", "2026-09-07 12:00:00", "SUCESSO");
        jdbc.update("UPDATE dbo.tb_log_integracao SET arquivado = 1 WHERE id = 2");
        var dto = consultar();
        assertEquals(0, etapa(dto, "DADOS").sucessosPeriodo());
        assertEquals(0, etapa(dto, "COMPROVANTE").sucessosPeriodo());
        assertEquals(0, etapa(dto, "COMPROVANTE").semConfirmacaoDatada());
    }

    @Test
    void dataFinalInclusivaNaoIncluiDiaSeguinte() {
        log(1, "cte1", "nf1", "SUCESSO", "SUCESSO", "2026-09-01 00:00:00", "2026-09-10 23:59:59", "SUCESSO");
        log(2, "cte2", "nf2", "SUCESSO", "SUCESSO", "2026-09-11 00:00:00", "2026-09-11 00:00:00", "SUCESSO");
        var dto = consultar();
        assertEquals(1, etapa(dto, "DADOS").sucessosPeriodo());
        assertEquals(1, etapa(dto, "COMPROVANTE").sucessosPeriodo());
        assertEquals(2, dto.evolucao().size());
    }

    @Test
    void filtroDeDestinoSeAplicaAoPeriodoEAoSaldo() {
        log(1, "cte1", "nf1", "PENDENTE_ORIGEM", "PENDENTE_FOTO", null, null, "BLOQUEADO_ORIGEM");
        var dto = repository.consultar(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-10"), List.of("PPG"));
        assertTrue(dto.etapas().isEmpty());
        assertTrue(dto.evolucao().isEmpty());
    }

    @Test
    void ausenteNaoHerdaraSucessoDaOutraEtapa() {
        log(1, "cte1", "nf1", null, "SUCESSO", null, "2026-09-08 12:00:00", "SUCESSO");
        assertEquals(1, etapa(consultar(), "DADOS").semConfirmacaoDatada());
        assertEquals(0, etapa(consultar(), "DADOS").sucessosPeriodo());
    }
}
