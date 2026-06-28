package com.example.satelite.services.etl;

public enum ResultadoRegistro {
    ENVIADO(false, false),
    IGNORADO(false, false),
    JA_PROCESSADO(false, false),
    PENDENTE_FOTO(false, false),
    ERRO(true, false),
    ERRO_INFRAESTRUTURA(true, true);

    private final boolean erro;
    private final boolean falhaInfraestrutura;

    ResultadoRegistro(boolean erro, boolean falhaInfraestrutura) {
        this.erro = erro;
        this.falhaInfraestrutura = falhaInfraestrutura;
    }

    boolean enviado() {
        return this == ENVIADO;
    }

    boolean ignorado() {
        return this == IGNORADO;
    }

    boolean jaProcessado() {
        return this == JA_PROCESSADO;
    }

    boolean pendenteFoto() {
        return this == PENDENTE_FOTO;
    }

    boolean erro() {
        return erro;
    }

    boolean falhaInfraestrutura() {
        return falhaInfraestrutura;
    }
}
