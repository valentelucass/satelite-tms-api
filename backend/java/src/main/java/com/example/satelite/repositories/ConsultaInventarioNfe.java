package com.example.satelite.repositories;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.springframework.data.domain.Pageable;
import com.example.satelite.models.LogIntegracaoModel;

public interface ConsultaInventarioNfe {
    Optional<List<LogIntegracaoModel>> tentarListarLoteNfe(
            String cliente, List<String> chaves, LocalDateTime agora, Pageable pagina);
    OptionalLong tentarContarLoteNfe(String cliente, List<String> chaves);
}
