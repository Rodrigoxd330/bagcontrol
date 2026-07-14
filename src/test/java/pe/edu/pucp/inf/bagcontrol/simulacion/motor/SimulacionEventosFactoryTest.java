package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.TipoEvento;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EstadoCapacidad;

import java.time.Instant;

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
}
