package com.example.satelite.services.ppg;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.satelite.clients.PpgClient;
import com.example.satelite.dto.ppg.PpgLoginRequestDTO;
import com.example.satelite.dto.ppg.PpgLoginResponseDTO;

@Service
public class PpgAuthService {
    private final PpgClient ppgClient;

    @Value("${PPG_API_USER}")
    private String email;
    @Value("${PPG_API_PASSWORD}")
    private String password;
    private String tokenAutenticado;
    private LocalDateTime dataExpiracao;

    public PpgAuthService(PpgClient ppgClient) {
        this.ppgClient = ppgClient;
    }

    public synchronized String obterTokenValido() {
        if (tokenAutenticado != null && LocalDateTime.now().isBefore(dataExpiracao)) {
            return tokenAutenticado;
        }

        PpgLoginRequestDTO credentials = new PpgLoginRequestDTO(email, password);
        PpgLoginResponseDTO response = ppgClient.login(credentials);
        this.tokenAutenticado = response.id();
        this.dataExpiracao = LocalDateTime.now().plusDays(13); 
        return this.tokenAutenticado;
    }
}
