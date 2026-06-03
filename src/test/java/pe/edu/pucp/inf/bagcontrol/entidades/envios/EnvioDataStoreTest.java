package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EnvioDataStoreTest {

    @Test
    void consumeSoloEnviosDesdeElInicioIncluidoHastaElFinExcluido() {
        EnvioDataStore store = new EnvioDataStore();
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata("LIM");

        store.agregarEnvio(crearEnvio("ANTERIOR", LocalDateTime.of(2026, 7, 20, 8, 14)), aeropuerto);
        store.agregarEnvio(crearEnvio("INICIO", LocalDateTime.of(2026, 7, 20, 8, 15)), aeropuerto);
        store.agregarEnvio(crearEnvio("FIN", LocalDateTime.of(2026, 7, 20, 13, 15)), aeropuerto);

        assertThat(store.obtenerEnviosEnVentana(
                LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 20, 13, 15)
        )).extracting(Envio::getIdPedido).containsExactly("INICIO");
        assertThat(store.getTotalEnviosCargados()).isEqualTo(3);
    }

    private Envio crearEnvio(String id, LocalDateTime fechaHora) {
        Envio envio = new Envio();
        envio.setIdPedido(id);
        envio.setFechaHora(fechaHora);
        return envio;
    }
}
