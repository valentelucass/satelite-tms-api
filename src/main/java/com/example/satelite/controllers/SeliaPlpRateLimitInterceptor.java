package com.example.satelite.controllers;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.example.satelite.services.selia.SeliaPlpRateLimitService;
import com.example.satelite.services.selia.SeliaPreShipmentListService;

@Component
public class SeliaPlpRateLimitInterceptor implements HandlerInterceptor {

    private static final String HEADER_CHAVE = "logistic-provider-api-key";

    private final SeliaPreShipmentListService plpService;
    private final SeliaPlpRateLimitService rateLimitService;

    public SeliaPlpRateLimitInterceptor(
            SeliaPreShipmentListService plpService,
            SeliaPlpRateLimitService rateLimitService
    ) {
        this.plpService = plpService;
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String chave = request.getHeader(HEADER_CHAVE);
        boolean permitido = plpService.chaveAutenticada(chave)
                ? rateLimitService.permitirChaveAutenticada(chave, request.getRemoteAddr())
                : rateLimitService.permitirIp(request.getRemoteAddr());
        if (permitido) {
            return true;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":\"ERROR\",\"messages\":[{\"type\":\"ERROR\",\"text\":\"Limite temporario de requisicoes excedido.\",\"code\":\"selia.plp.rate_limit\"}]}");
        return false;
    }
}
