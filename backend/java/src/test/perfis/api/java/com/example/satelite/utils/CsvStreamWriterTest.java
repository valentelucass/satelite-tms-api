package com.example.satelite.utils;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvStreamWriterTest {
    @Test
    void preservesUnicodeQuotesSeparatorsAndNulls() {
        var out = new ByteArrayOutputStream();
        var csv = new CsvStreamWriter(out);
        csv.escreverCabecalho("ação", "motivo");
        csv.escreverLinha("com;separador", "ele disse \"ok\"", null);
        csv.flush();
        assertEquals("\"ação\";\"motivo\"" + System.lineSeparator()
                + "\"com;separador\";\"ele disse \"\"ok\"\"\";\"\"" + System.lineSeparator(), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void neutralizesFormulaPrefixes() {
        var out = new ByteArrayOutputStream();
        var csv = new CsvStreamWriter(out);
        csv.escreverLinha("=SUM(1)", "+1", "-1", "@value", "", 10);
        csv.flush();
        assertEquals("\"'=SUM(1)\";\"'+1\";\"'-1\";\"'@value\";\"\";\"10\"" + System.lineSeparator(), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void propagatesOutputFailureInsteadOfReportingExportSuccess() {
        var csv = new CsvStreamWriter(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("unit full disk"); }
        });
        csv.escreverLinha("short buffered row");
        assertThrows(UncheckedIOException.class, csv::flush);
    }
}
