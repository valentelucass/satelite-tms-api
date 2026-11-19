package com.example.satelite.services.origem.sftp.vedacit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Configuração tipada dos perfis consumidos pelo futuro WORK-SFTP-CLIENTES. */
@Component
public class SftpClientesProperties {
    private static final Pattern IDENTIFICADOR_VALIDO = Pattern.compile("[A-Z0-9_]+$");
    private final Environment environment;

    public SftpClientesProperties(Environment environment) {
        this.environment = environment;
    }

    public boolean habilitado() {
        return Boolean.parseBoolean(environment.getProperty("SFTP_CLIENTS_ENABLED", "false").trim());
    }

    public List<Perfil> perfisHabilitados() {
        if (!habilitado()) {
            return List.of();
        }

        List<String> identificadores = identificadores();
        List<Perfil> perfis = new ArrayList<>();
        for (String identificador : identificadores) {
            String prefixo = "SFTP_CLIENT_" + identificador + "_";
            if (!Boolean.parseBoolean(environment.getProperty(prefixo + "ENABLED", "false").trim())) {
                continue;
            }
            perfis.add(new Perfil(
                    identificador,
                    obrigatoria(prefixo + "HOST"),
                    inteiro(prefixo + "PORT", 22, 1, 65535),
                    obrigatoria(prefixo + "USERNAME"),
                    obrigatoria(prefixo + "PASSWORD"),
                    obrigatoria(prefixo + "BASE_PATH"),
                    obrigatoria(prefixo + "PATH"),
                    obrigatoria(prefixo + "HOST_KEY_SHA256"),
                    inteiro(prefixo + "MAX_FILES_PER_CYCLE", 100, 1, 500),
                    longo(prefixo + "MAX_FILE_SIZE_BYTES", 26_214_400L, 1L, Integer.MAX_VALUE),
                    longo(prefixo + "STABLE_FOR_MS", 120_000L, 0L, Long.MAX_VALUE)
            ));
        }
        if (perfis.isEmpty()) {
            throw new IllegalStateException("WORK-SFTP-CLIENTES habilitado sem perfil SFTP ativo");
        }
        return List.copyOf(perfis);
    }

    private List<String> identificadores() {
        String configurados = environment.getProperty("SFTP_CLIENTS_IDS", "");
        Set<String> unicos = new LinkedHashSet<>();
        for (String valor : configurados.split(",")) {
            String identificador = valor.trim().toUpperCase(Locale.ROOT);
            if (identificador.isEmpty()) {
                continue;
            }
            if (!IDENTIFICADOR_VALIDO.matcher(identificador).matches()) {
                throw new IllegalArgumentException("Identificador SFTP inválido: " + identificador);
            }
            if (!unicos.add(identificador)) {
                throw new IllegalArgumentException("Identificador SFTP duplicado: " + identificador);
            }
        }
        if (unicos.isEmpty()) {
            throw new IllegalStateException("SFTP_CLIENTS_IDS deve informar ao menos um perfil");
        }
        return List.copyOf(unicos);
    }

    private String obrigatoria(String chave) {
        String valor = environment.getProperty(chave, "").trim();
        if (valor.isEmpty()) {
            throw new IllegalStateException("Configuração SFTP obrigatória ausente: " + chave);
        }
        return valor;
    }

    private int inteiro(String chave, int padrao, int minimo, int maximo) {
        try {
            int valor = Integer.parseInt(environment.getProperty(chave, String.valueOf(padrao)).trim());
            if (valor < minimo || valor > maximo) {
                throw new IllegalArgumentException("Configuração SFTP fora do limite: " + chave);
            }
            return valor;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Configuração SFTP numérica inválida: " + chave, e);
        }
    }

    private long longo(String chave, long padrao, long minimo, long maximo) {
        try {
            long valor = Long.parseLong(environment.getProperty(chave, String.valueOf(padrao)).trim());
            if (valor < minimo || valor > maximo) {
                throw new IllegalArgumentException("Configuração SFTP fora do limite: " + chave);
            }
            return valor;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Configuração SFTP numérica inválida: " + chave, e);
        }
    }

    public record Perfil(
            String identificador,
            String host,
            int porta,
            String usuario,
            String senha,
            String diretorioBase,
            String diretorioCliente,
            String hostKeySha256,
            int maxArquivosPorCiclo,
            long maxTamanhoArquivoBytes,
            long estabilidadeMinimaMs
    ) {
        public Perfil {
            diretorioBase = VedacitSftpPathPolicy.validarDiretorioBase(diretorioBase);
            diretorioCliente = VedacitSftpPathPolicy.validarDiretorioCliente(diretorioBase, diretorioCliente);
        }
    }
}
