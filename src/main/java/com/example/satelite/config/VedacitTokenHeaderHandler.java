package com.example.satelite.config;

import java.util.Set;

import javax.xml.namespace.QName;

import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPEnvelope;
import jakarta.xml.soap.SOAPException;
import jakarta.xml.soap.SOAPHeader;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.handler.MessageContext;
import jakarta.xml.ws.handler.soap.SOAPHandler;
import jakarta.xml.ws.handler.soap.SOAPMessageContext;

public class VedacitTokenHeaderHandler implements SOAPHandler<SOAPMessageContext> {

    private final String token;

    public VedacitTokenHeaderHandler(String token) {
        this.token = token;
    }

    @Override
    public boolean handleMessage(SOAPMessageContext context) {
        Boolean outbound = (Boolean) context.get(MessageContext.MESSAGE_OUTBOUND_PROPERTY);
        if (!Boolean.TRUE.equals(outbound)) {
            return true;
        }

        try {
            SOAPMessage message = context.getMessage();
            SOAPEnvelope envelope = message.getSOAPPart().getEnvelope();
            SOAPHeader header = envelope.getHeader();

            if (header == null) {
                header = envelope.addHeader();
            }

            SOAPElement tokenElement = header.addChildElement(new QName("Token", "Token", ""));
            tokenElement.addTextNode(token);
            message.saveChanges();
            return true;
        } catch (SOAPException e) {
            throw new IllegalStateException("Erro ao adicionar header Token no SOAP da Vedacit", e);
        }
    }

    @Override
    public boolean handleFault(SOAPMessageContext context) {
        return true;
    }

    @Override
    public void close(MessageContext context) {
    }

    @Override
    public Set<QName> getHeaders() {
        return Set.of(new QName("Token", "Token", ""));
    }
}
