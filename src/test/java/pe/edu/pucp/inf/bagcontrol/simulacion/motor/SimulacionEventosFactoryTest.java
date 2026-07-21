package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.TipoEvento;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EstadoCapacidad;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoVueloDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimulacionEventosFactoryTest {

    @Test
    void clasificaVacioSoloCuandoLaOcupacionEsCero() {
        assertThat(SimulacionEventosFactory.calcularEstadoAeropuerto(0, 1000))
                .isEqualTo(EstadoCapacidad.VACIO);
        assertThat(SimulacionEventosFactory.calcularEstadoAeropuerto(1, 1000))
                .isEqualTo(EstadoCapacidad.VERDE);
        assertThat(SimulacionEventosFactory.calcularEstadoAeropuerto(100, 400))
                .isEqualTo(EstadoCapacidad.VERDE);
        assertThat(SimulacionEventosFactory.calcularEstadoAeropuerto(10, 1000))
                .isNotEqualTo(EstadoCapacidad.VACIO);
    }

    @Test
    void salidaTotalYEntradaPosteriorRecalculanEstado() {
        assertThat(SimulacionEventosFactory.calcularEstadoAeropuerto(10, 1000))
                .isEqualTo(EstadoCapacidad.VERDE);
        assertThat(SimulacionEventosFactory.calcularEstadoAeropuerto(0, 1000))
                .isEqualTo(EstadoCapacidad.VACIO);
        assertThat(SimulacionEventosFactory.calcularEstadoAeropuerto(1, 1000))
                .isEqualTo(EstadoCapacidad.VERDE);
    }

    @Test
    void conservaSaturacionComoAlertaOperativa() {
        SimulacionEventosFactory factory = new SimulacionEventosFactory(new ConfiguracionColapsoDTO());
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata("LIM");
        aeropuerto.setCapacidadAlmacen(10);
        SimulacionState state = new SimulacionState("test");

        var actualizado = factory.crearEventoAeropuerto(aeropuerto, 10, Instant.parse("2026-07-20T08:15:00Z"), state);
        var alerta = factory.crearAlertaAeropuertoSaturado(actualizado);

        assertThat(alerta.getTipo()).isEqualTo(TipoEvento.ALERTA_AEROPUERTO_SATURADO);
        assertThat(alerta.getPorcentajeOcupacion()).isEqualTo(100.0);
    }

    @Test
    void regenerarMismoVueloNoDuplicaEnvioNiCapacidad() {
        SimulacionEventosFactory factory = new SimulacionEventosFactory(null);
        EventoVueloDTO existente = vuelo(TipoEvento.VUELO_DESPEGA, 7, List.of("ENV-ANON"));
        EventoVueloDTO regenerado = vuelo(TipoEvento.VUELO_DESPEGA, 7, List.of("ENV-ANON"));

        factory.fusionarEventoVuelo(existente, regenerado);

        assertThat(existente.getCodigoEnvios()).containsExactly("ENV-ANON");
        assertThat(existente.getCantidadMaletas()).isEqualTo(7);
        assertThat(existente.getPorcentajeOcupacion()).isEqualTo(7.0);
    }

    @Test
    void fotografiaMasRecienteReemplazaEstadoYConservaEnviosEnTransitoSinDuplicados() {
        SimulacionEventosFactory factory = new SimulacionEventosFactory(null);
        EventoVueloDTO existente = vuelo(TipoEvento.VUELO_DESPEGA, 7, List.of("ENV-ANTERIOR"));
        EventoVueloDTO vigente = vuelo(
                TipoEvento.VUELO_DESPEGA,
                5,
                List.of("ENV-ANTERIOR", "ENV-VIGENTE", "ENV-VIGENTE")
        );
        vigente.setPorcentajeOcupacion(61.0);
        vigente.setEstado(EstadoCapacidad.AMARILLO);

        factory.fusionarEventoVuelo(existente, vigente);

        assertThat(existente.getCodigoEnvios()).containsExactly("ENV-ANTERIOR", "ENV-VIGENTE");
        assertThat(existente.getCantidadMaletas()).isEqualTo(5);
        assertThat(existente.getPorcentajeOcupacion()).isEqualTo(61.0);
        assertThat(existente.getEstado()).isEqualTo(EstadoCapacidad.AMARILLO);
    }

    @Disabled("fusionarEventoVuelo no recibe el estado de cancelacion de una asignacion individual")
    @Test
    void asignacionCanceladaRequiereContratoExplicitoAntesDeEliminarlaDeCodigoEnvios() {
        // La cancelacion actual se representa con VUELO_CANCELADO y se procesa fuera de esta
        // fusion. Con solo dos fotografias no es posible distinguir una asignacion cancelada
        // de un envio anterior que sigue en transito y debe conservarse hasta el aterrizaje.
    }

    @Test
    void mismoEnvioPuedeEstarEnEventosDeTramosDistintos() {
        EventoVueloDTO primerTramo = vuelo(TipoEvento.VUELO_DESPEGA, 3, List.of("ENV-ESCALA"));
        primerTramo.setCodigoVuelo(10L);
        EventoVueloDTO segundoTramo = vuelo(TipoEvento.VUELO_DESPEGA, 3, List.of("ENV-ESCALA"));
        segundoTramo.setCodigoVuelo(20L);

        assertThat(primerTramo.getCodigoEnvios()).containsExactly("ENV-ESCALA");
        assertThat(segundoTramo.getCodigoEnvios()).containsExactly("ENV-ESCALA");
        assertThat(primerTramo.claveInstanciaVuelo()).isNotEqualTo(segundoTramo.claveInstanciaVuelo());
    }

    private EventoVueloDTO vuelo(TipoEvento tipo, int maletas, List<String> envios) {
        EventoVueloDTO evento = new EventoVueloDTO(
                tipo, "2028-07-20T10:00:00Z", 1L, "AAA", "BBB", EstadoCapacidad.VERDE,
                maletas, "2028-07-20T10:00", "2028-07-20T11:00",
                "2028-07-20T10:00:00Z", "2028-07-20T11:00:00Z", new ArrayList<>(envios));
        evento.setCapacidadMax(100);
        evento.setPorcentajeOcupacion(maletas);
        return evento;
    }
}
