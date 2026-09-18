package com.example.satelite.services.vedacit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.example.satelite.vedacit.cte.ICTe;
import com.example.satelite.vedacit.cte.sgt.RetornoOfCTeS2VakUsz;
import com.example.satelite.vedacit.cte.embarcador.cte.CTe;
import com.example.satelite.vedacit.nfe.*;

class VedacitConsultaReconciliacaoServiceTest {
    final VedacitIntegrationService integracao=mock(VedacitIntegrationService.class);
    final ICTe cte=mock(ICTe.class);
    final INFe nfe=mock(INFe.class);
    final VedacitConsultaReconciliacaoService service=new VedacitConsultaReconciliacaoService(integracao);
    @BeforeEach void preparar() throws Exception {
        when(integracao.criarPortaCte()).thenReturn(cte);when(integracao.criarPortaNFe()).thenReturn(nfe);
        when(integracao.executarSoapComPrazo(any(),anyString())).thenAnswer(i->((Callable<?>)i.getArgument(0)).call());
    }
    static <T> JAXBElement<T> elemento(Class<T> tipo,T valor) { return new JAXBElement<>(new QName("teste"),tipo,valor); }
    @Test void autorizacaoNegadaFechaCircuitoSemGuardarMensagemExterna() {
        var r=new RetornoOfCTeS2VakUsz();r.setStatus(false);r.setMensagem(elemento(String.class,"Autorização negada: segredo"));
        when(cte.buscarCTePorChave(anyString())).thenReturn(r);
        var resultado=service.consultarXml("4".repeat(44));
        assertEquals("CONSULTA_SEM_PERMISSAO",resultado.codigo());assertTrue(resultado.interromperFonte());
        assertFalse(resultado.toString().contains("segredo"));
    }
    @Test void faultRealDeMetodoSemPermissaoEhIdentificadoNasDuasConsultas() throws Exception {
        var factory=jakarta.xml.soap.SOAPFactory.newInstance();
        when(cte.buscarCTePorChave(anyString())).thenThrow(new jakarta.xml.ws.soap.SOAPFaultException(
                factory.createFault("Método BuscarCTePorChave sem permissão. detalhe-restrito",
                        new QName("http://schemas.xmlsoap.org/soap/envelope/","Client"))));
        when(nfe.buscarCanhotoPorChaveNFe(anyString())).thenThrow(new jakarta.xml.ws.soap.SOAPFaultException(
                factory.createFault("Método BuscarCanhotoPorChaveNFe sem permissão. detalhe-restrito",
                        new QName("http://schemas.xmlsoap.org/soap/envelope/","Client"))));
        for(var resultado:java.util.List.of(service.consultarXml("4".repeat(44)),service.consultarComprovante("3".repeat(44)))) {
            assertEquals("CONSULTA_SEM_PERMISSAO",resultado.codigo());
            assertTrue(resultado.interromperFonte());
            assertFalse(resultado.toString().contains("detalhe-restrito"));
        }
    }
    @Test void xmlExatoConfirmaPresencaMasNaoInventaDataHistorica() {
        var objeto=new CTe();objeto.setChave(elemento(String.class,"4".repeat(44)));
        var r=new RetornoOfCTeS2VakUsz();r.setStatus(true);r.setObjeto(elemento(CTe.class,objeto));
        r.setDataRetorno(elemento(String.class,"13/09/2026 02:00"));
        when(cte.buscarCTePorChave(anyString())).thenReturn(r);
        assertEquals("XML_PRESENTE_NO_DESTINO_DATA_A_CONFERIR",service.consultarXml("4".repeat(44)).codigo());
    }
    @Test void respostaDeOutroCteNuncaConfirmaOConsultado() {
        var objeto=new CTe();objeto.setChave(elemento(String.class,"5".repeat(44)));
        var r=new RetornoOfCTeS2VakUsz();r.setStatus(true);r.setObjeto(elemento(CTe.class,objeto));
        when(cte.buscarCTePorChave(anyString())).thenReturn(r);
        assertEquals("RESPOSTA_COM_IDENTIDADE_DIVERGENTE",service.consultarXml("4".repeat(44)).codigo());
    }
    @Test void canhotoPorNfeSemCteNaoLiberaTimeout() {
        var objeto=new CanhotoNotaFiscal();objeto.setArquivo(elemento(String.class,"arquivo_simulado"));
        var r=new RetornoOfCanhotoNotaFiscal6G1AQySx();r.setStatus(true);r.setObjeto(elemento(CanhotoNotaFiscal.class,objeto));
        when(nfe.buscarCanhotoPorChaveNFe(anyString())).thenReturn(r);
        assertEquals("COMPROVANTE_NFE_PRESENTE_VINCULO_CTE_A_CONFERIR",service.consultarComprovante("3".repeat(44)).codigo());
        verifyNoInteractions(cte);
    }
    @Test void falhaDeTransporteInterrompeSomenteAFonteConsultada() {
        when(nfe.buscarCanhotoPorChaveNFe(anyString())).thenThrow(new IllegalStateException("Read timed out"));
        var r=service.consultarComprovante("3".repeat(44));assertEquals("CONSULTA_INDISPONIVEL",r.codigo());assertTrue(r.interromperFonte());
    }
}
