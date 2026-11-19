package com.example.satelite.services.etl;

import static com.example.satelite.dto.auditoria.ReconciliacaoVedacitDTO.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.example.satelite.repositories.ReconciliacaoVedacitRepository;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClient;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpClientFactory;
import com.example.satelite.services.origem.sftp.vedacit.VedacitSftpInventory;
import com.example.satelite.services.vedacit.VedacitConsultaReconciliacaoService;

/** Uma pagina por passagem; todo item recebe evidencia, mesmo quando uma fonte externa esta bloqueada. */
@Service
public class ReconciliacaoVedacitService {
    private final ReconciliacaoVedacitRepository repository;
    private final VedacitSftpClientFactory clientes;
    private final VedacitConsultaReconciliacaoService consulta;
    private final ReconciliacaoVedacitAcoesService acoes;
    @Value("${VEDACIT_RECONCILIACAO_PAGE_SIZE:100}") private int tamanhoPagina=100;
    @Value("${VEDACIT_RECONCILIACAO_RECOVERY_ENABLED:true}") private boolean recuperar=true;
    @Value("${VEDACIT_RECONCILIACAO_PACING_MS:1000}") private long pausaMs=1000;
    @Value("${VEDACIT_XML_SOURCE_RETRY_COOLDOWN_MS:1800000}") private long cooldownXmlMs=1800000;
    public ReconciliacaoVedacitService(ReconciliacaoVedacitRepository repository,VedacitSftpClientFactory clientes,
            VedacitConsultaReconciliacaoService consulta,ReconciliacaoVedacitAcoesService acoes) {
        this.repository=repository;this.clientes=clientes;this.consulta=consulta;this.acoes=acoes;
    }

    public boolean processarPagina(Execucao execucao,Supplier<LocalDateTime> relogio,LocalDateTime fimJanela,
            LocalDateTime proximaRevisao) {
        List<Candidato> pagina=repository.pagina(execucao,Math.max(1,Math.min(200,tamanhoPagina)));
        if(pagina.isEmpty()) { repository.concluir(execucao.id(),relogio.get()); return true; }
        var fontes=new Fontes(execucao);
        for(Candidato candidato:pagina) {
            if(Thread.currentThread().isInterrupted()) break;
            if(!relogio.get().isBefore(fimJanela)) { repository.pausar(execucao.id(),relogio.get()); break; }
            Revisao resultado;
            try { resultado=revisar(candidato,fontes,relogio.get()); }
            catch(RuntimeException e) {
                // Nao grava mensagem externa, XML ou chave fiscal em relatorios.
                resultado=new Revisao("FALHA_NA_CONFERENCIA","FALHA_NA_CONFERENCIA","REVISAR_PROXIMA_NOITE",
                        "Falha isolada neste registro; demais documentos continuam em revisao.");
            }
            if(Thread.currentThread().isInterrupted()) break;
            repository.registrar(execucao,candidato,resultado,relogio.get(),proximaRevisao);
            pausar();
        }
        return false;
    }

    Revisao revisar(Candidato c,Fontes f,LocalDateTime agora) {
        String acao=c.arquivado()?"ARQUIVADO_CONFERIDO_SEM_REATIVACAO":"CONFERIDO";
        String xml="NAO_APLICAVEL".equals(c.statusXml())?"NAO_APLICAVEL":"XML_STATUS_EXIGE_ANALISE";
        String pod="NAO_APLICAVEL".equals(c.statusPod())?"NAO_APLICAVEL":"COMPROVANTE_STATUS_EXIGE_ANALISE";
        if(!c.identidadeValida()) {
            f.inventario();
            String motivo=f.bloqueios.get("SFTP");
            return new Revisao("IDENTIDADE_FISCAL_INCOMPLETA",motivo!=null?motivo:
                    f.rejeitados.contains(c.referencia())?"ARQUIVO_CONTINUA_SEM_IDENTIFICACAO":"IDENTIDADE_EXIGE_CORRELACAO",
                    acao,"Falta identidade fiscal verificavel; nenhum CT-e foi escolhido por aproximacao.");
        }
        if(c.xmlConfirmadoEm()!=null) xml="XML_CONFIRMADO_LOCAL";
        else if(c.xmlSucessoHistorico() || "SUCESSO".equals(c.statusXml()) || c.arquivado() ||
                ("ERRO_DESTINO".equals(c.statusXml()) && !origemRecuperavel(c))) {
            xml=f.consultar("CONSULTA_XML",c.cte(),()->consulta.consultarXml(c.cte()));
        } else if("PENDENTE_ORIGEM".equals(c.statusXml()) || "ERRO_DESTINO".equals(c.statusXml())) {
            if(!origemRecuperavel(c)) xml="XML_RETIDO_PARA_CONFERENCIA";
            else if(c.dataXml()!=null && c.dataXml().isAfter(agora.minusNanos(Math.max(1000,cooldownXmlMs)*1_000_000)))
                xml="XML_AGUARDA_INTERVALO_ENTRE_TENTATIVAS";
            else if(c.arquivado() || !"VEDACIT".equals(c.cliente())) xml="XML_AGUARDA_CORRELACAO_COM_CLIENTE";
            else if(!recuperar) xml="XML_ELEGIVEL_RECUPERACAO_DESABILITADA";
            else if(f.bloqueios.containsKey("ENVIO_XML")) xml=f.bloqueios.get("ENVIO_XML");
            else {
                f.inventario();
                boolean origemBloqueada=f.bloqueios.containsKey("ORIGEM_XML");
                boolean xmlSftp=false;
                if(origemBloqueada && f.cliente!=null && !f.bloqueios.containsKey("SFTP")) {
                    try { xmlSftp=f.cliente.buscarXmlCte(c.cte(),c.nfe()).isPresent(); }
                    catch(RuntimeException e) { f.bloquear("SFTP","SFTP_INDISPONIVEL"); }
                }
                if(origemBloqueada && !xmlSftp) xml=f.bloqueios.get("ORIGEM_XML");
                else {
                    var r=acoes.recuperarXml(c);
                    xml="ENVIADO".equals(r.codigo())?"XML_RECUPERADO_CONFIRMADO":"XML_"+r.codigo();
                    if("ENVIADO".equals(r.codigo())) acao="XML_RECUPERADO";
                    if(r.erro()!=null) {
                        if(r.erro().contains("401") || r.erro().contains("403") || r.erro().contains("AUTENTICACAO")) {
                            f.bloquear("ORIGEM_XML","XML_ORIGEM_SEM_PERMISSAO"); xml="XML_ORIGEM_SEM_PERMISSAO";
                        } else if(r.erro().matches("ORIGEM_XML_HTTP_(429|5\\d\\d).*")) {
                            f.bloquear("ORIGEM_XML","XML_ORIGEM_INDISPONIVEL"); xml="XML_ORIGEM_INDISPONIVEL";
                        } else if(r.erro().startsWith("ORIGEM_XML_AUSENTE") || r.erro().startsWith("ORIGEM_XML_HTTP_404")) xml="XML_AUSENTE_NA_ORIGEM";
                        else if(!r.erro().startsWith("ORIGEM_XML_") && infraestruturaIndisponivel(r.erro()) && !"ENVIADO".equals(r.codigo()))
                            f.bloquear("ENVIO_XML","ENVIO_XML_SUSPENSO_APOS_FALHA");
                    }
                }
            }
        }

        if(c.podConfirmado()) {
            pod=c.dataPodConfiavel()?"COMPROVANTE_CONFIRMADO_LOCAL":"COMPROVANTE_ACEITO_DATA_HISTORICA_INCERTA";
            if(recuperar && !c.arquivado() && (!"SUCESSO".equals(c.statusPod()) || !"SUCESSO".equals(c.classificacao()))
                    && acoes.conciliarAceiteLocal(c)) acao="ACEITE_LOCAL_CONCILIADO_SEM_ENVIO";
        } else if("TIMEOUT_AMBIGUO".equals(c.classificacao()) || "BLOQUEADO_DESTINO".equals(c.classificacao())
                || "SUCESSO".equals(c.statusPod()) || c.arquivado()) {
            pod=f.consultar("CONSULTA_POD",c.nfe(),()->consulta.consultarComprovante(c.nfe()));
            acao=c.arquivado()?acao:"ENVIO_RETIDO_AGUARDA_CONFIRMACAO_EXATA";
        } else if("PENDENTE_FOTO".equals(c.statusPod()) || "ERRO_DESTINO".equals(c.statusPod())) {
            f.inventario();
            if(f.bloqueios.containsKey("SFTP")) pod=f.bloqueios.get("SFTP");
            else if(!f.pares.contains(c.nfe()+":"+c.ctePod())) pod="COMPROVANTE_EXATO_AUSENTE_NO_SFTP";
            else if(c.xmlConfirmadoEm()==null) pod="COMPROVANTE_AGUARDA_CONFIRMACAO_XML";
            else if(!recuperar) pod="COMPROVANTE_ELEGIVEL_RECUPERACAO_DESABILITADA";
            else if(!"VEDACIT".equals(c.cliente())) pod="COMPROVANTE_AGUARDA_CORRELACAO_COM_CLIENTE";
            else if(f.bloqueios.containsKey("ENVIO_POD")) pod=f.bloqueios.get("ENVIO_POD");
            else {
                var r=acoes.recuperarComprovante(c,f.cliente);
                pod="ENVIADO".equals(r.codigo())?"COMPROVANTE_RECUPERADO_CONFIRMADO":"COMPROVANTE_"+r.codigo();
                if("ENVIADO".equals(r.codigo())) acao="COMPROVANTE_RECUPERADO";
                else if(infraestruturaIndisponivel(r.erro()) && !"ENVIADO".equals(r.codigo()))
                    f.bloquear("ENVIO_POD","ENVIO_COMPROVANTE_SUSPENSO_APOS_FALHA");
            }
        }
        return new Revisao(xml,pod,acao,"Conferencia por identidade exata; datas historicas e bloqueios de envio preservados.");
    }

    static boolean origemRecuperavel(Candidato c) {
        return !c.xmlSucessoHistorico() && !c.arquivado() &&
                ((c.erroXml()!=null && c.erroXml().startsWith("ORIGEM_XML_")) ||
                 ("PENDENTE_ORIGEM".equals(c.statusXml()) && c.tentativasXml()==0 && c.dataXml()==null
                    && (c.erroXml()==null || c.erroXml().isBlank())));
    }
    static boolean infraestruturaIndisponivel(String erro) {
        String m=erro==null?"":erro.toLowerCase(java.util.Locale.ROOT);
        return m.contains("timeout") || m.contains("timed out") || m.contains("connection")
                || m.contains("sem confirma") || m.contains("desconhecido") || m.contains("anterior_em_andamento")
                || m.contains("401") || m.contains("403") || m.contains("autoriza") || m.contains("unauthor")
                || m.matches(".*\\b(429|500|502|503|504)\\b.*");
    }
    private void pausar() {
        try { if(pausaMs>0) Thread.sleep(Math.min(60000,pausaMs)); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    final class Fontes {
        final Execucao execucao;
        final Map<String,String> bloqueios=new HashMap<>();
        final Map<String,String> cache=new HashMap<>();
        final Set<String> pares=new HashSet<>();
        final Set<String> rejeitados=new HashSet<>();
        VedacitSftpClient cliente;
        boolean inventariado;
        Fontes(Execucao e) {
            execucao=e;
            carregar("SFTP",e.bloqueioSftp());carregar("CONSULTA_XML",e.bloqueioConsultaXml());
            carregar("CONSULTA_POD",e.bloqueioConsultaPod());carregar("ORIGEM_XML",e.bloqueioOrigemXml());
            carregar("ENVIO_XML",e.bloqueioEnvioXml());carregar("ENVIO_POD",e.bloqueioEnvioPod());
        }
        void carregar(String fonte,String motivo) { if(motivo!=null) bloqueios.put(fonte,motivo); }
        void bloquear(String fonte,String motivo) { repository.bloquearFonte(execucao.id(),fonte,motivo);bloqueios.put(fonte,motivo); }
        String consultar(String fonte,String chave,Supplier<ResultadoConsulta> operacao) {
            if(bloqueios.containsKey(fonte)) return bloqueios.get(fonte);
            return cache.computeIfAbsent(fonte+":"+chave,k->{
                var r=operacao.get(); if(r.interromperFonte()) bloquear(fonte,r.codigo()); return r.codigo();
            });
        }
        void inventario() {
            if(inventariado || bloqueios.containsKey("SFTP")) return;
            inventariado=true;
            try {
                cliente=clientes.criarClientesHabilitados().stream().filter(c->"VEDACIT".equals(c.identificador()))
                        .findFirst().orElseThrow().cliente();
                VedacitSftpInventory i=cliente.listarInventarioComprovantes();
                i.documentosValidos().forEach(d->pares.add(d.chaveNfe()+":"+d.chaveCte()));
                i.rejeitados().forEach(d->rejeitados.add(d.caminhoRelativo()));
            } catch(RuntimeException e) { bloquear("SFTP","SFTP_INDISPONIVEL"); }
        }
    }
}
