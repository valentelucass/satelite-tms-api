package com.example.satelite.utils;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CteXmlValidatorTest {
    private static final String CTE = "1".repeat(44), NFE = "2".repeat(44);

    @Test
    void correlatesInvoiceInsideCorrectCte() {
        assertTrue(valid(document(CTE, NFE)));
        assertFalse(valid(document("3".repeat(44), NFE)));
        assertFalse(valid(document(CTE, "4".repeat(44))));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"<invalid", "<outro/>", "<infCte Id='CTe111'/>", "<?xml version='1.0'?>"})
    void malformedOrUnrelatedXmlIsRejected(String xml) { assertFalse(valid(xml)); }

    @Test
    void doesNotResolveExternalEntities(@TempDir Path directory) throws Exception {
        Path secret = directory.resolve("unit-secret.txt");
        Files.writeString(secret, NFE);
        String xml = "<!DOCTYPE cteProc [<!ENTITY file SYSTEM '" + secret.toUri() + "'>]>" + document(CTE, "&file;");
        assertFalse(valid(xml));
    }

    @Test
    void doesNotAcceptKeysInCommentsOrUnrelatedNamespace() {
        assertFalse(valid("<outro><!-- " + CTE + " " + NFE + " --></outro>"));
        assertFalse(valid(document(CTE, NFE).replace("http://www.portalfiscal.inf.br/cte", "urn:unrelated")));
    }

    @Test
    void rejectsMultipleCteIdentitiesInsteadOfChoosingArbitrarily() {
        String item = document(CTE, NFE);
        assertFalse(valid("<batch>" + item + item + "</batch>"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-key", "1234"})
    void invalidExpectedKeysAreRejected(String key) {
        assertFalse(CteXmlValidator.corresponde(document(CTE, NFE).getBytes(StandardCharsets.UTF_8), key, NFE));
        assertFalse(CteXmlValidator.corresponde(document(CTE, NFE).getBytes(StandardCharsets.UTF_8), CTE, key));
    }

    private boolean valid(String xml) {
        return CteXmlValidator.corresponde(xml == null ? null : xml.getBytes(StandardCharsets.UTF_8), CTE, NFE);
    }

    private String document(String cte, String nfe) {
        return "<cteProc xmlns='http://www.portalfiscal.inf.br/cte'><CTe><infCte Id='CTe" + cte
                + "'><infCTeNorm><infDoc><infNFe><chave>" + nfe
                + "</chave></infNFe></infDoc></infCTeNorm></infCte></CTe></cteProc>";
    }
}
