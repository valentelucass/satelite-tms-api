package com.example.satelite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.satelite.clients.PpgClient;
import com.example.satelite.clients.RodogarciaClient;
import com.example.satelite.repositories.ControleCursorRepository;
import com.example.satelite.repositories.IntegracaoAuditoriaQueryRepository;
import com.example.satelite.repositories.LogIntegracaoRepository;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
		"RODOGARCIA_API_BASE_URL=http://localhost",
		"RODOGARCIA_CUSTOMER_OCCURRENCES_PATH=/ocorrencias",
		"RODOGARCIA_TOKEN_PPG=token-teste",
		"RODOGARCIA_TOKEN_VEDACIT=token-teste",
		"PPG_API_BASE_URL=http://localhost",
		"PPG_API_USER=usuario-teste",
		"PPG_API_PASSWORD=senha-teste",
		"PPG_ENTREGADOR_ID=1",
		"PPG_CNPJ_TRANSPORTADORA=12345678000199",
		"VEDACIT_API_BASE_URL=http://localhost",
		"VEDACIT_API_TOKEN=token-teste",
		"APP_SCHEDULER_ENABLED=false",
		"ESL_MIN_INTERVAL_BETWEEN_REQUESTS_MS=0",
		"INTEGRATION_SCHEDULER_INTERVAL_MS=60000"
})
class SateliteApplicationTests {

	@MockitoBean
	private PpgClient ppgClient;

	@MockitoBean
	private RodogarciaClient rodogarciaClient;

	@MockitoBean
	private LogIntegracaoRepository logIntegracaoRepository;

	@MockitoBean
	private ControleCursorRepository controleCursorRepository;

	@MockitoBean
	private IntegracaoAuditoriaQueryRepository integracaoAuditoriaQueryRepository;

	@Test
	void contextLoads() {
	}

}
