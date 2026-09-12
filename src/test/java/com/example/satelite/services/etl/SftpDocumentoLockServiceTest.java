package com.example.satelite.services.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class SftpDocumentoLockServiceTest {
    private static final String NFE = "35260760642774001209550010002330311658124736";
    private static final String CTE = "35260760960473000758570030000521251702802407";

    @Test
    void criaRecursoDeterministicoPorClienteENotas() {
        assertEquals(
                "SATELITE_TMS:VEDACIT:COMPROVANTE:" + NFE + ":" + CTE,
                SftpDocumentoLockService.recurso("cliente_a", NFE, CTE)
        );
        assertEquals(SftpDocumentoLockService.recurso("CLIENTE_A",NFE,CTE),
                SftpDocumentoLockService.recurso("CLIENTE_B",NFE,CTE));
        assertEquals(SftpDocumentoLockService.recurso("VEDACIT_XML",NFE,CTE),
                SftpDocumentoLockService.recurso("VEDACIT_XML","3".repeat(44),CTE));
    }

    @Test
    void mesmoDocumentoAninhadoUsaUmaSessaoELiberaAposFalha() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var conn=mock(Connection.class);
        var statement=mock(PreparedStatement.class);
        var rs=mock(ResultSet.class);
        doAnswer(i -> i.<ConnectionCallback<Object>>getArgument(0).doInConnection(conn))
                .when(jdbc).execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Object>>any());
        when(conn.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true); when(rs.getInt(1)).thenReturn(0);
        var service=new SftpDocumentoLockService(jdbc);
        assertThrows(IllegalStateException.class, () -> service.executarComLock("A",NFE,CTE,
                () -> service.executarComLock("B",NFE,CTE, () -> { throw new IllegalStateException("falha"); })));
        assertEquals("ok",service.executarComLock("B",NFE,CTE,() -> "ok").orElseThrow());
        verify(conn,times(4)).prepareStatement(anyString()); // dois acquire/release; aninhado reutiliza a sessão.
    }

    @Test
    void rejeitaClienteOuDocumentoInvalidos() {
        assertThrows(IllegalArgumentException.class,
                () -> SftpDocumentoLockService.recurso("cliente-a", NFE, CTE));
        assertThrows(IllegalArgumentException.class,
                () -> SftpDocumentoLockService.recurso("CLIENTE_A", "invalida", CTE));
    }

    @Test
    void mantemDocumentoPendenteQuandoLockNaoEstaDisponivel() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        SftpDocumentoLockService service = new SftpDocumentoLockService(jdbcTemplate);
        doAnswer(invocacao -> {
            ConnectionCallback<Object> callback = invocacao.getArgument(0);
            return callback.doInConnection(connection);
        }).when(jdbcTemplate).execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Object>>any());
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(-1);

        boolean executou = service.executarComLock("CLIENTE_A", NFE, CTE, () -> {
            throw new AssertionError("A operação não pode executar sem lock");
        }).isPresent();

        assertFalse(executou);
        verify(connection).prepareStatement(anyString());
    }
}
