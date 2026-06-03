package pe.edu.pucp.inf.bagcontrol.planificacion.utils;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanificadorUtilsTest {

    @Test
    void calculaDeadlineDe24HorasParaEnvioDentroDelMismoContinente() {
        Envio envio = crearEnvio("PED-1", "LIM", "BOG", LocalDateTime.of(2026, 7, 20, 8, 15));
        Map<String, Aeropuerto> aeropuertos = Map.of(
                "LIM", crearAeropuerto("LIM", "AMERICA", -5),
                "BOG", crearAeropuerto("BOG", "AMERICA", -5)
        );

        assertThat(PlanificadorUtils.calcularDeadlineSla(envio, aeropuertos))
                .isEqualTo(Instant.parse("2026-07-21T08:15:00Z"));
        assertThat(PlanificadorUtils.obtenerTipoSla(envio, aeropuertos))
                .isEqualTo("24H_MISMO_CONTINENTE");
    }

    @Test
    void calculaDeadlineDe48HorasParaEnvioIntercontinental() {
        Envio envio = crearEnvio("PED-2", "LIM", "MAD", LocalDateTime.of(2026, 8, 15, 13, 32));
        Map<String, Aeropuerto> aeropuertos = Map.of(
                "LIM", crearAeropuerto("LIM", "AMERICA", -5),
                "MAD", crearAeropuerto("MAD", "EUROPA", 1)
        );

        assertThat(PlanificadorUtils.calcularDeadlineSla(envio, aeropuertos))
                .isEqualTo(Instant.parse("2026-08-17T13:32:00Z"));
        assertThat(PlanificadorUtils.obtenerTipoSla(envio, aeropuertos))
                .isEqualTo("48H_INTERCONTINENTAL");
    }

    @Test
    void aceptaArriboExactamenteEnDeadlineYRechazaUnMinutoDespues() {
        Envio envio = crearEnvio("PED-3", "LIM", "BOG", LocalDateTime.of(2026, 7, 20, 8, 15));
        Map<String, Aeropuerto> aeropuertos = Map.of(
                "LIM", crearAeropuerto("LIM", "AMERICA", -5),
                "BOG", crearAeropuerto("BOG", "AMERICA", -5)
        );

        assertThat(PlanificadorUtils.excedePlazoMaximo(
                envio, crearItinerario("2026-07-21T08:15:00Z"), aeropuertos
        )).isFalse();
        assertThat(PlanificadorUtils.excedePlazoMaximo(
                envio, crearItinerario("2026-07-21T08:16:00Z"), aeropuertos
        )).isTrue();
    }

    @Test
    void rechazaItinerarioQueSuperaCapacidadEnAeropuertoDeEscala() {
        Envio envio = crearEnvio("PED-4", "LIM", "MAD", LocalDateTime.of(2026, 7, 20, 8, 15));
        envio.setCantidadMaletas(3);
        Map<String, Aeropuerto> aeropuertos = Map.of(
                "LIM", crearAeropuerto("LIM", "AMERICA", -5, 10),
                "BOG", crearAeropuerto("BOG", "AMERICA", -5, 2),
                "MAD", crearAeropuerto("MAD", "EUROPA", 1, 10)
        );
        Itinerario itinerario = new Itinerario(List.of(
                crearVuelo("LIM", "BOG", "2026-07-20T09:00:00Z", "2026-07-20T10:00:00Z"),
                crearVuelo("BOG", "MAD", "2026-07-20T11:00:00Z", "2026-07-20T13:00:00Z")
        ));
        SolucionRuta solucion = new SolucionRuta();
        solucion.agregarAsignacion(envio, itinerario);

        assertThat(PlanificadorUtils.solucionRespetaCapacidadAeropuertos(
                solucion, aeropuertos, Map.of("LIM", 3)
        )).isFalse();
    }

    @Test
    void aceptaItinerarioConEscalaCuandoLiberaEspacioAntesDeContinuar() {
        Envio envio = crearEnvio("PED-5", "LIM", "MAD", LocalDateTime.of(2026, 7, 20, 8, 15));
        envio.setCantidadMaletas(3);
        Map<String, Aeropuerto> aeropuertos = Map.of(
                "LIM", crearAeropuerto("LIM", "AMERICA", -5, 10),
                "BOG", crearAeropuerto("BOG", "AMERICA", -5, 3),
                "MAD", crearAeropuerto("MAD", "EUROPA", 1, 10)
        );
        Itinerario itinerario = new Itinerario(List.of(
                crearVuelo("LIM", "BOG", "2026-07-20T09:00:00Z", "2026-07-20T10:00:00Z"),
                crearVuelo("BOG", "MAD", "2026-07-20T11:00:00Z", "2026-07-20T13:00:00Z")
        ));
        SolucionRuta solucion = new SolucionRuta();
        solucion.agregarAsignacion(envio, itinerario);

        assertThat(PlanificadorUtils.solucionRespetaCapacidadAeropuertos(
                solucion, aeropuertos, Map.of("LIM", 3)
        )).isTrue();
    }

    private Envio crearEnvio(String id, String origen, String destino, LocalDateTime fechaHoraUtc) {
        Envio envio = new Envio();
        envio.setIdPedido(id);
        envio.setOrigenIata(origen);
        envio.setDestinoIata(destino);
        envio.setFechaHora(fechaHoraUtc);
        envio.setCantidadMaletas(1);
        return envio;
    }

    private Aeropuerto crearAeropuerto(String iata, String continente, int gmt) {
        return crearAeropuerto(iata, continente, gmt, 100);
    }

    private Aeropuerto crearAeropuerto(String iata, String continente, int gmt, int capacidad) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setContinente(continente);
        aeropuerto.setGmt(gmt);
        aeropuerto.setCapacidadAlmacen(capacidad);
        return aeropuerto;
    }

    private Itinerario crearItinerario(String llegadaUtc) {
        VueloInstanciado vuelo = new VueloInstanciado();
        vuelo.setFechaHoraSalidaUtc(Instant.parse("2026-07-20T09:00:00Z"));
        vuelo.setFechaHoraLlegadaUtc(Instant.parse(llegadaUtc));
        return new Itinerario(List.of(vuelo));
    }

    private VueloInstanciado crearVuelo(String origen, String destino, String salidaUtc, String llegadaUtc) {
        Vuelo vuelo = new Vuelo(origen, destino, LocalTime.NOON, LocalTime.NOON, 10);
        return new VueloInstanciado(
                vuelo,
                LocalDateTime.of(2026, 7, 20, 12, 0),
                LocalDateTime.of(2026, 7, 20, 12, 0),
                Instant.parse(salidaUtc),
                Instant.parse(llegadaUtc),
                0
        );
    }
}
