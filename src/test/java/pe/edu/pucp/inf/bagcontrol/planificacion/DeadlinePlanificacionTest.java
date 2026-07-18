package pe.edu.pucp.inf.bagcontrol.planificacion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.deadline.DeadlinePlanificacion;
import pe.edu.pucp.inf.bagcontrol.planificacion.metricas.MetricasPlanificacionBloque;
import pe.edu.pucp.inf.bagcontrol.planificacion.metricas.PlanificacionInstrumentacion;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.motor.SimulacionJob;
import pe.edu.pucp.inf.bagcontrol.simulacion.motor.SimulacionManager;
import pe.edu.pucp.inf.bagcontrol.simulacion.motor.WebSocketPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeadlinePlanificacionTest {
    @AfterEach void limpiar() { DeadlinePlanificacion.limpiar(); PlanificacionInstrumentacion.limpiar(); }

    @Test
    void deadlineNoAlcanzadoYAlcanzadoRegistranLaFase() {
        MetricasPlanificacionBloque metricas = PlanificacionInstrumentacion.iniciar();
        DeadlinePlanificacion.iniciar(System.currentTimeMillis() + 60_000);
        assertThat(DeadlinePlanificacion.alcanzado("CANDIDATOS")).isFalse();
        DeadlinePlanificacion.iniciar(System.currentTimeMillis() - 1);
        assertThat(DeadlinePlanificacion.alcanzado("CANDIDATOS")).isTrue();
        assertThat(metricas.isDeadlineAlcanzado()).isTrue();
        assertThat(metricas.getFaseDeadline()).isEqualTo("CANDIDATOS");
    }

    @Test
    void configuracionTemporalAprobadaEsExplicita() {
        assertThat(SimulacionJob.SA_INICIAL_MS).isEqualTo(35_000);
        assertThat(SimulacionJob.SA_MAXIMO_MS).isEqualTo(40_000);
        assertThat(SimulacionJob.MARGEN_SEGURIDAD_MS).isEqualTo(4_000);
    }

    @Test
    void aceptaSoloK60_120_180() {
        PlanificadorService servicio = mock(PlanificadorService.class);
        when(servicio.obtenerAeropuertosSnapshot()).thenReturn(List.of());
        when(servicio.obtenerVuelosBaseSnapshot()).thenReturn(List.of());
        when(servicio.obtenerIncidenciasSnapshot()).thenReturn(List.of());
        SimulacionManager manager = new SimulacionManager(
                servicio, mock(AeropuertoRepository.class), mock(WebSocketPublisher.class));
        LocalDateTime inicio = LocalDateTime.of(2026, 7, 20, 8, 0);
        LocalDateTime fin = inicio.plusHours(24);
        for (int k : List.of(60, 120, 180)) {
            assertThat(manager.crearJob(inicio, fin, k, "TABU", "BENCHMARK")).isNotBlank();
        }
        for (int k : List.of(0, -1, 30, 240, 300)) {
            assertThatThrownBy(() -> manager.crearJob(inicio, fin, k, "TABU", "BENCHMARK"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("60, 120 o 180");
        }
    }

    @Test
    void parametrosTabuAprobadosSonLosPredeterminados() throws Exception {
        assertThat(PlanificadorService.TABU_ITERACIONES_DEFAULT).isEqualTo(120);
        assertThat(PlanificadorService.TABU_TENURE_DEFAULT).isEqualTo(12);
        assertThat(PlanificadorService.TABU_MAX_VECINOS_DEFAULT).isEqualTo(50);
        assertThat(pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo.TabuSearch.MAX_ITERACIONES_SIN_MEJORA)
                .isEqualTo(15);
    }
}
