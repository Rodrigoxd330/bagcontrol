package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.NuevoEnvioDTO;

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

    @Test
    void altaActualizacionYEliminacionCrudSeReflejanEnLaVentanaSinDuplicados() {
        EnvioDataStore store = new EnvioDataStore();
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata("SPIM");

        Envio creado = store.agregarEnvio(new NuevoEnvioDTO(
                "SPIM", "SKBO", 5, "CLI-1", "2026-07-20T08:30:00"
        ), aeropuerto);

        assertThat(store.obtenerEnviosEnVentana(
                LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 25, 8, 15)
        )).extracting(Envio::getIdPedido).containsExactly(creado.getIdPedido());

        creado.setDestinoIata("SBBR");
        creado.setCantidadMaletas(7);
        creado.setFechaHora(LocalDateTime.of(2026, 7, 21, 9, 0));
        store.upsert(creado);

        assertThat(store.obtenerEnviosEnVentana(
                LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 25, 8, 15)
        )).singleElement().satisfies(envio -> {
            assertThat(envio.getDestinoIata()).isEqualTo("SBBR");
            assertThat(envio.getCantidadMaletas()).isEqualTo(7);
        });

        assertThat(store.eliminar(creado.getIdPedido())).isTrue();
        assertThat(store.obtenerEnviosEnVentana(
                LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 25, 8, 15)
        )).isEmpty();
    }

    @Test
    void unOverrideCrudReemplazaAlEnvioBaseConElMismoId() {
        EnvioDataStore store = new EnvioDataStore();
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata("SPIM");
        store.agregarEnvio(crearEnvio("SPIM-0001", LocalDateTime.of(2026, 7, 20, 8, 30)), aeropuerto);

        Envio actualizado = crearEnvio("SPIM-0001", LocalDateTime.of(2026, 7, 20, 9, 0));
        actualizado.setCantidadMaletas(9);
        store.upsert(actualizado);

        assertThat(store.obtenerEnviosEnVentana(
                LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 20, 10, 0)
        )).singleElement().satisfies(envio -> {
            assertThat(envio.getFechaHora()).isEqualTo(LocalDateTime.of(2026, 7, 20, 9, 0));
            assertThat(envio.getCantidadMaletas()).isEqualTo(9);
        });
    }

    private Envio crearEnvio(String id, LocalDateTime fechaHora) {
        Envio envio = new Envio();
        envio.setIdPedido(id);
        envio.setFechaHora(fechaHora);
        return envio;
    }
}
