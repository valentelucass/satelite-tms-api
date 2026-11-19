package com.example.satelite.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class CsvStreamWriter {

    private final BufferedWriter writer;

    public CsvStreamWriter(OutputStream outputStream) {
        this.writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
    }

    public void escreverCabecalho(String... colunas) {
        escreverLinha((Object[]) colunas);
    }

    public void escreverLinha(Object... valores) {
        try {
            for (int indice = 0; indice < valores.length; indice++) {
                if (indice > 0) {
                    writer.write(';');
                }
                writer.write(escapar(valores[indice]));
            }
            writer.newLine();
        } catch (IOException ex) {
            throw new UncheckedIOException("Nao foi possivel gerar o CSV.", ex);
        }
    }

    public void flush() {
        try {
            writer.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException("Nao foi possivel finalizar o CSV.", ex);
        }
    }

    private String escapar(Object valor) {
        String texto = valor == null ? "" : String.valueOf(valor);
        if (!texto.isEmpty() && "=+-@".indexOf(texto.charAt(0)) >= 0) {
            texto = "'" + texto;
        }
        return '"' + texto.replace("\"", "\"\"") + '"';
    }
}
