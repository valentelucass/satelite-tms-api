package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Consumer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.satelite.dto.etl.QuarentenaErroManualDTO;
import com.example.satelite.dto.etl.QuarentenaErroManualExportacaoDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.IntegracaoAuditoriaQueryRepository;
import com.example.satelite.repositories.LogIntegracaoRepository;

@Service
public class QuarentenaService {

    private static final List<String> DESTINOS_CONSULTA = List.of("PPG", "VEDACIT", "SELIA");
    private static final Set<String> DESTINOS_CONSULTA_VALIDOS = Set.copyOf(DESTINOS_CONSULTA);
    private static final Set<String> DESTINOS_REPROCESSAVEIS = Set.of("PPG", "VEDACIT");
    private static final Pattern TAGS_HTML = Pattern.compile("<[^>]+>");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");

    private final LogIntegracaoRepository logIntegracaoRepository;
    private final IntegracaoAuditoriaQueryRepository integracaoAuditoriaQueryRepository;

    public QuarentenaService(
            LogIntegracaoRepository logIntegracaoRepository,
            IntegracaoAuditoriaQueryRepository integracaoAuditoriaQueryRepository
    ) {
        this.logIntegracaoRepository = logIntegracaoRepository;
        this.integracaoAuditoriaQueryRepository = integracaoAuditoriaQueryRepository;
    }

    public List<LogIntegracaoModel> findQuarentenaByDestino(String destino) {
        return logIntegracaoRepository.findQuarentenaByDestino(normalizarDestinoConsulta(destino));
    }

    public Page<QuarentenaErroManualDTO> buscarErrosManuais(Pageable pageable, List<String> destinosRecebidos) {
        return logIntegracaoRepository.findErrosManuais(normalizarDestinosConsulta(destinosRecebidos), pageable)
                .map(this::mapearErroManual);
    }

    public void exportarErrosManuais(
            List<String> destinosRecebidos,
            Consumer<QuarentenaErroManualDTO> consumidor
    ) {
        integracaoAuditoriaQueryRepository.exportarErrosQuarentena(
                normalizarDestinosConsulta(destinosRecebidos),
                item -> consumidor.accept(mapearErroManual(item))
        );
    }

    @Transactional
    public ResultadoReprocessamento reprocessar(String destino) {
        String destinoNormalizado = normalizarDestinoReprocessamento(destino);
        int quantidade = logIntegracaoRepository.resetarQuarentenaByDestino(destinoNormalizado);
        return new ResultadoReprocessamento(destinoNormalizado, quantidade);
    }

    public QuarentenaErroManualDTO mapearErroManual(LogIntegracaoModel registro) {
        return new QuarentenaErroManualDTO(
                registro.getId(),
                registro.getSistemaDestino(),
                registro.getChaveNfe(),
                extrairNumeroNf(registro.getChaveNfe()),
                maiorTentativas(registro),
                erroLimpo(registro),
                dataUltimaTentativa(registro)
        );
    }

    private QuarentenaErroManualDTO mapearErroManual(QuarentenaErroManualExportacaoDTO registro) {
        return new QuarentenaErroManualDTO(
                registro.id(),
                registro.destino(),
                registro.chaveNfe(),
                extrairNumeroNf(registro.chaveNfe()),
                maiorTentativas(registro.tentativasDados(), registro.tentativasCanhoto()),
                erroLimpo(registro.mensagemErroDados(), registro.mensagemErroCanhoto(), registro.erro()),
                dataUltimaTentativa(
                        registro.dataProcessamento(),
                        registro.dataProcessamentoDados(),
                        registro.dataProcessamentoCanhoto()
                )
        );
    }

    public String erroLimpo(LogIntegracaoModel registro) {
        return erroLimpo(
                registro != null ? registro.getMensagemErroDados() : null,
                registro != null ? registro.getMensagemErroCanhoto() : null,
                registro != null ? registro.getErro() : null
        );
    }

    private String erroLimpo(String mensagemErroDados, String mensagemErroCanhoto, String erro) {
        String mensagem = primeiraMensagemErro(mensagemErroDados, mensagemErroCanhoto, erro);
        if (mensagem == null || mensagem.isBlank()) {
            return "Motivo indisponivel";
        }

        String semHtml = TAGS_HTML.matcher(mensagem).replaceAll(" ");
        StringBuilder limpo = new StringBuilder();
        for (String linha : semHtml.split("\\R")) {
            String texto = limparLinhaErro(linha);
            if (texto == null) {
                continue;
            }

            if (!limpo.isEmpty()) {
                limpo.append(' ');
            }
            limpo.append(texto);
        }

        String normalizado = ESPACOS.matcher(limpo.toString()).replaceAll(" ").trim();
        return normalizado.isBlank() ? "Motivo indisponivel" : normalizado;
    }

    private String primeiraMensagemErro(String mensagemErroDados, String mensagemErroCanhoto, String erro) {
        if (mensagemErroDados != null && !mensagemErroDados.isBlank()) {
            return mensagemErroDados;
        }

        if (mensagemErroCanhoto != null && !mensagemErroCanhoto.isBlank()) {
            return mensagemErroCanhoto;
        }

        return erro;
    }

    private String limparLinhaErro(String linha) {
        String texto = linha != null ? linha.trim() : "";
        if (texto.isBlank()
                || texto.startsWith("at ")
                || texto.startsWith("Suppressed:")) {
            return null;
        }

        String semCausa = texto.replaceFirst(
                "^Caused by:\\s*[a-zA-Z0-9_.$]+(?:Exception|Error):\\s*",
                ""
        );
        String semPrefixoTecnico = semCausa.replaceFirst(
                "^[a-zA-Z0-9_.$]+(?:Exception|Error):\\s*",
                ""
        ).trim();

        return semPrefixoTecnico.isBlank() ? null : semPrefixoTecnico;
    }

    private int maiorTentativas(LogIntegracaoModel registro) {
        return maiorTentativas(registro.getTentativasDados(), registro.getTentativasCanhoto());
    }

    private int maiorTentativas(Integer tentativasDados, Integer tentativasCanhoto) {
        return Math.max(valorTentativas(tentativasDados), valorTentativas(tentativasCanhoto));
    }

    private int valorTentativas(Integer tentativas) {
        return tentativas != null ? tentativas : 0;
    }

    private LocalDateTime dataUltimaTentativa(LogIntegracaoModel registro) {
        return dataUltimaTentativa(
                registro.getDataProcessamento(),
                registro.getDataProcessamentoDados(),
                registro.getDataProcessamentoCanhoto()
        );
    }

    private LocalDateTime dataUltimaTentativa(
            LocalDateTime dataProcessamento,
            LocalDateTime dataProcessamentoDados,
            LocalDateTime dataProcessamentoCanhoto
    ) {
        LocalDateTime data = maiorData(dataProcessamento, dataProcessamentoDados);
        return maiorData(data, dataProcessamentoCanhoto);
    }

    private LocalDateTime maiorData(LocalDateTime atual, LocalDateTime candidata) {
        if (candidata == null) {
            return atual;
        }

        if (atual == null || candidata.isAfter(atual)) {
            return candidata;
        }

        return atual;
    }

    private Long extrairNumeroNf(String chaveNfe) {
        if (chaveNfe == null || chaveNfe.length() < 34) {
            return null;
        }

        try {
            return Long.parseLong(chaveNfe.substring(25, 34));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> normalizarDestinosConsulta(List<String> destinosRecebidos) {
        List<String> destinos = destinosRecebidos == null ? List.of() : destinosRecebidos.stream()
                .map(this::normalizarTexto)
                .filter(destino -> !destino.isEmpty())
                .distinct()
                .toList();
        if (destinos.isEmpty()) {
            return DESTINOS_CONSULTA;
        }
        if (destinos.stream().anyMatch(destino -> !DESTINOS_CONSULTA_VALIDOS.contains(destino))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destino de integração inválido.");
        }
        return destinos;
    }

    private String normalizarDestinoConsulta(String destino) {
        List<String> destinos = normalizarDestinosConsulta(destino == null ? List.of() : List.of(destino));
        if (destinos.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destino de integração inválido.");
        }
        return destinos.get(0);
    }

    private String normalizarDestinoReprocessamento(String destino) {
        if (destino == null || destino.isBlank()) {
            throw new IllegalArgumentException("Destino invalido. Use PPG ou VEDACIT.");
        }

        String destinoNormalizado = normalizarTexto(destino);
        if (!DESTINOS_REPROCESSAVEIS.contains(destinoNormalizado)) {
            throw new IllegalArgumentException("Destino invalido. Use PPG ou VEDACIT.");
        }

        return destinoNormalizado;
    }

    private String normalizarTexto(String destino) {
        return destino == null ? "" : destino.trim().toUpperCase(Locale.ROOT);
    }

    public record ResultadoReprocessamento(String destino, int quantidadeNotas) {
    }
}
