package pe.edu.pucp.inf.bagcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BagcontrolApplicationTests {

	@Autowired
	private EnvioDataStore envioDataStore;

	@Test
	void contextLoads() {
	}

	@Test
	void cargaVentanaSim5dJulioDesdeTiempoCeroSinEliminarDatos() {
		validarVentanaSim5d(
				LocalDateTime.of(2026, 7, 20, 8, 15),
				LocalDateTime.of(2026, 7, 25, 8, 15)
		);
	}

	@Test
	void cargaVentanaSim5dAgostoDesdeTiempoCeroSinEliminarDatos() {
		validarVentanaSim5d(
				LocalDateTime.of(2026, 8, 15, 13, 32),
				LocalDateTime.of(2026, 8, 20, 13, 32)
		);
	}

	private void validarVentanaSim5d(LocalDateTime inicio, LocalDateTime fin) {
		int totalAntes = envioDataStore.getTotalEnviosCargados();
		var envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);

		assertThat(envios).isNotEmpty();
		assertThat(envios)
				.extracting(Envio::getFechaHora)
				.allSatisfy(fechaHora -> {
					assertThat(fechaHora).isAfterOrEqualTo(inicio);
					assertThat(fechaHora).isBefore(fin);
				});
		assertThat(envioDataStore.getTotalEnviosCargados()).isEqualTo(totalAntes);
	}
}
