package com.example.satelite.services.etl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.satelite.dto.etl.QuarentenaErroManualDTO;
import com.example.satelite.models.LogIntegracaoModel;
import com.example.satelite.repositories.LogIntegracaoRepository;

@Service
public class QuarentenaService {

    private static final Set<String> DESTINOS_VALIDOS = Set.of("PPG", "VEDACIT");
    private static final Pattern TAGS_HTML = Pattern.compile("<[^>]+>");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");

    private final LogIntegracaoRepository logIntegracaoRepository;

    public QuarentenaService(LogIntegracaoRepository logIntegracaoRepository) {
        this.logIntegracaoRepository = logIntegracaoRepository;
    }

    public List<LogIntegracaoModel> findQuarentenaByDestino(String destino) {
        return logIntegracaoRepository.findQuarentenaByDestino(normalizarDestino(destino));
    }

    public Page<QuarentenaErroManualDTO> buscarErrosManuais(Pageable pageable) {
        return logIntegracaoRepository.findErrosManuais(pageable)
                .map(this::mapearErroManual);
    }

    @Transactional
    public ResultadoReprocessamento reprocessar(String destino) {
        String destinoNormalizado = normalizarDestino(destino);
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

    public String erroLimpo(LogIntegracaoModel registro) {
        String mensagem = primeiraMensagemErro(registro);
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

    private String primeiraMensagemErro(LogIntegracaoModel registro) {
        if (registro == null) {
            return null;
        }

        if (registro.getMensagemErroDados() != null && !registro.getMensagemErroDados().isBlank()) {
            return registro.getMensagemErroDados();
        }

        if (registro.getMensagemErroCanhoto() != null && !registro.getMensagemErroCanhoto().isBlank()) {
            return registro.getMensagemErroCanhoto();
        }

        return registro.getErro();
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
        return Math.max(
                valorTentativas(registro.getTentativasDados()),
                valorTentativas(registro.getTentativasCanhoto())
        );
    }

    private int valorTentativas(Integer tentativas) {
        return tentativas != null ? tentativas : 0;
    }

    private LocalDateTime dataUltimaTentativa(LogIntegracaoModel registro) {
        LocalDateTime data = registro.getDataProcessamento();
        data = maiorData(data, registro.getDataProcessamentoDados());
        return maiorData(data, registro.getDataProcessamentoCanhoto());
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

    private String normalizarDestino(String destino) {
        if (destino == null || destino.isBlank()) {
            throw new IllegalArgumentException("Destino invalido. Use PPG ou VEDACIT.");
        }

        String destinoNormalizado = destino.trim().toUpperCase(Locale.ROOT);
        if (!DESTINOS_VALIDOS.contains(destinoNormalizado)) {
            throw new IllegalArgumentException("Destino invalido. Use PPG ou VEDACIT.");
        }

        return destinoNormalizado;
    }

    public record ResultadoReprocessamento(String destino, int quantidadeNotas) {
    }
}
