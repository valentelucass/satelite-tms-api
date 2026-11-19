package com.example.satelite.dto.auditoria;

import java.util.List;

/** Histórico técnico paginado de ciclos SFTP, sem dados fiscais ou de conexão. */
public record WorkSftpClienteExecucoesPaginadasDTO(
        List<WorkSftpClienteStatusDTO> itens,
        PaginacaoDTO paginacao
) { }
