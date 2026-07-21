package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.NuevoEnvioDTO;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EnvioDataStoreTest {

    @Test
    void inventarioOperativoAislaAeropuertosSimulacionYDuplicados() {
        EnvioDataStore store = new EnvioDataStore();
        Envio spimA = envioOperativo("A", "SPIM", 2);
        Envio spimB = envioOperativo("B", "SPIM", 1);
        Envio sabe = envioOperativo("C", "SABE", 4);
        Envio ekch = envioOperativo("D", "EKCH", 3);
        Envio simulado = envioOperativo("SIM", "SPIM", 99);
        simulado.setEsOperacionDia(false);

        store.upsert(spimA);
        store.upsert(spimB);
        store.upsert(sabe);
        store.upsert(ekch);
        store.upsert(simulado);
        store.upsert(spimA);

        assertThat(store.obtenerEnviosOperativosEnAeropuerto("SPIM"))
                .extracting(Envio::getIdPedido).containsExactly("A", "B");
        assertThat(store.obtenerEnviosOperativosEnAeropuerto("SABE"))
                .extracting(Envio::getIdPedido).containsExactly("C");
        assertThat(store.obtenerEnviosOperativosEnAeropuerto("EKCH"))
                .extracting(Envio::getIdPedido).containsExactly("D");
        assertThat(store.obtenerEnviosOperativosEnAeropuerto("VACIO")).isEmpty();
        assertThat(store.obtenerInventarioOperativoPorAeropuerto())
                .doesNotContainKey("VACIO")
                .allSatisfy((iata, envios) -> assertThat(envios)
                        .allMatch(Envio::isEsOperacionDia));
        assertThat(store.obtenerEnviosOperativosEnAeropuerto("SPIM").stream()
                .mapToInt(Envio::getCantidadMaletas).sum()).isEqualTo(3);
    }

    @Test
    void movimientoOperativoSoloCambiaLaProyeccionOperativa() {
        EnvioDataStore store = new EnvioDataStore();
        Envio envio = envioOperativo("A", "SPIM", 2);
        store.upsert(envio);

        store.moverEnvioOperativo("A", "SABE");

        assertThat(store.obtenerEnviosOperativosEnAeropuerto("SPIM")).isEmpty();
        assertThat(store.obtenerEnviosOperativosEnAeropuerto("SABE"))
                .extracting(Envio::getIdPedido).containsExactly("A");
        assertThat(envio.getOrigenIata()).isEqualTo("SPIM");
    }

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
                "SPIM", "SKBO", 5, "CLI-1", "2026-07-20T08:30:00", false
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

    private Envio envioOperativo(String id, String origen, int maletas) {
        Envio envio = crearEnvio(id, LocalDateTime.of(2026, 7, 21, 17, 0));
        envio.setOrigenIata(origen);
        envio.setDestinoIata("DEST");
        envio.setCantidadMaletas(maletas);
        envio.setActivo(true);
        envio.setEsOperacionDia(true);
        return envio;
    }
}
