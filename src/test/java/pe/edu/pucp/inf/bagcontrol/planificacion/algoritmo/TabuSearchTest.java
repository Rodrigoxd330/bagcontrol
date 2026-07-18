package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TabuSearchTest {

    @Test
    void deadlineVencidoFinalizaOrdenadamenteConSolucionFactible() {
        TabuSearch tabuSearch = new TabuSearch(new FitnessEvaluator());
        long inicio = System.currentTimeMillis();

        var solucion = tabuSearch.ejecutar(
                List.of(), Map.of(), List.of(), Map.of(), Set.of(), inicio - 1, 1
        );

        assertThat(solucion).isNotNull();
        assertThat(solucion.getAsignaciones()).isEmpty();
        assertThat(System.currentTimeMillis() - inicio).isLessThan(1_000L);
    }

    @Test
    void deadlineVencidoNoDescartaUnaRutaDuranteLaConstruccionInicial() {
        TabuSearch tabuSearch = new TabuSearch(new FitnessEvaluator());
        Envio envio = new Envio();
        envio.setIdPedido("PED-1");
        envio.setOrigenIata("LIM");
        envio.setDestinoIata("BOG");
        envio.setFechaHora(LocalDateTime.of(2026, 7, 20, 8, 0));
        envio.setCantidadMaletas(2);
        Aeropuerto origen = crearAeropuerto("LIM");
        Aeropuerto destino = crearAeropuerto("BOG");
        Vuelo vueloBase = new Vuelo("LIM", "BOG", LocalTime.NOON, LocalTime.NOON, 10);
        VueloInstanciado vuelo = new VueloInstanciado(
                vueloBase,
                LocalDateTime.of(2026, 7, 20, 9, 0),
                LocalDateTime.of(2026, 7, 20, 10, 0),
                Instant.parse("2026-07-20T09:00:00Z"),
                Instant.parse("2026-07-20T10:00:00Z"),
                0
        );

        var solucion = tabuSearch.ejecutar(
                List.of(envio), Map.of("LIM-BOG", List.of(new Itinerario(List.of(vuelo)))),
                List.of(origen, destino), Map.of(), Set.of(envio.getIdPedido()),
                System.currentTimeMillis() - 1, 1
        );

        assertThat(solucion.getAsignaciones()).hasSize(1);
        assertThat(solucion.getAsignaciones().get(0).getItinerario()).isNotNull();
    }

    @Test
    void usaEscalaAntesDeLlevarUnVueloDirectoPorEncimaDeOchentaPorCiento() {
        TabuSearch tabuSearch = new TabuSearch(new FitnessEvaluator());
        Envio envio = new Envio();
        envio.setIdPedido("PED-BALANCE");
        envio.setOrigenIata("LIM");
        envio.setDestinoIata("BOG");
        envio.setFechaHora(LocalDateTime.of(2026, 7, 20, 8, 0));
        envio.setCantidadMaletas(2);
        VueloInstanciado directo = crearVuelo(
                "LIM", "BOG", "2026-07-20T09:00:00Z", "2026-07-20T10:00:00Z", 7
        );
        Itinerario escala = new Itinerario(List.of(
                crearVuelo("LIM", "UIO", "2026-07-20T09:15:00Z", "2026-07-20T10:15:00Z", 0),
                crearVuelo("UIO", "BOG", "2026-07-20T11:00:00Z", "2026-07-20T12:00:00Z", 0)
        ));

        var solucion = tabuSearch.ejecutar(
                List.of(envio),
                Map.of("LIM-BOG", List.of(new Itinerario(List.of(directo)), escala)),
                List.of(crearAeropuerto("LIM"), crearAeropuerto("UIO"), crearAeropuerto("BOG")),
                Map.of(), Set.of(envio.getIdPedido()), System.currentTimeMillis() - 1, 1
        );

        assertThat(solucion.getAsignaciones().get(0).getItinerario().getCantidadVuelos()).isEqualTo(2);
    }

    @Test
    void conservaVueloDirectoCuandoSuOcupacionProyectadaEstaBajoOchentaPorCiento() {
        TabuSearch tabuSearch = new TabuSearch(new FitnessEvaluator());
        Envio envio = new Envio();
        envio.setIdPedido("PED-DIRECTO");
        envio.setOrigenIata("LIM");
        envio.setDestinoIata("BOG");
        envio.setFechaHora(LocalDateTime.of(2026, 7, 20, 8, 0));
        envio.setCantidadMaletas(2);
        Itinerario directo = new Itinerario(List.of(crearVuelo(
                "LIM", "BOG", "2026-07-20T09:00:00Z", "2026-07-20T10:00:00Z", 3
        )));
        Itinerario escala = new Itinerario(List.of(
                crearVuelo("LIM", "UIO", "2026-07-20T09:15:00Z", "2026-07-20T10:15:00Z", 0),
                crearVuelo("UIO", "BOG", "2026-07-20T11:00:00Z", "2026-07-20T12:00:00Z", 0)
        ));

        var solucion = tabuSearch.ejecutar(
                List.of(envio), Map.of("LIM-BOG", List.of(directo, escala)),
                List.of(crearAeropuerto("LIM"), crearAeropuerto("UIO"), crearAeropuerto("BOG")),
                Map.of(), Set.of(envio.getIdPedido()), System.currentTimeMillis() - 1, 1
        );

        assertThat(solucion.getAsignaciones().get(0).getItinerario().getCantidadVuelos()).isEqualTo(1);
    }

    private VueloInstanciado crearVuelo(
            String origen,
            String destino,
            String salidaUtc,
            String llegadaUtc,
            int ocupacion
    ) {
        Vuelo vuelo = new Vuelo(origen, destino, LocalTime.NOON, LocalTime.NOON, 10);
        return new VueloInstanciado(
                vuelo,
                LocalDateTime.ofInstant(Instant.parse(salidaUtc), java.time.ZoneOffset.UTC),
                LocalDateTime.ofInstant(Instant.parse(llegadaUtc), java.time.ZoneOffset.UTC),
                Instant.parse(salidaUtc),
                Instant.parse(llegadaUtc),
                ocupacion
        );
    }

    private Aeropuerto crearAeropuerto(String iata) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setContinente("AMERICA");
        aeropuerto.setCapacidadAlmacen(100);
        return aeropuerto;
    }
}
