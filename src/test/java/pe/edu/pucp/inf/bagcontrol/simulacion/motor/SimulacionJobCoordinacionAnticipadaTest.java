package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoVueloDTO;

import static org.assertj.core.api.Assertions.assertThat;

class SimulacionJobCoordinacionAnticipadaTest {

    @Test
    void hundimientoUsaFormulaAprobada() {
        assertThat(SimulacionJob.calcularHundimientoMs(35_000, 20_000)).isEqualTo(11_000);
    }

    @Test
    void hundimientoNuncaSuperaLaMitadDeF() {
        assertThat(SimulacionJob.calcularHundimientoMs(35_000, 1_000)).isEqualTo(17_500);
    }

    @Test
    void hundimientoNuncaEsNegativo() {
        assertThat(SimulacionJob.calcularHundimientoMs(35_000, 40_000)).isZero();
    }

    @Test
    void segundoCalculoSeProgramaEnOrigenMasH() {
        long origen = 10_000;
        long h = SimulacionJob.calcularHundimientoMs(35_000, 2_000);
        assertThat(origen + h).isEqualTo(27_500);
    }

    @Test
    void bloquePreparadoPermaneceGuardadoHastaSuFrontera() {
        var preparado = preparado(1, 7, 45_000, Set.of());
        assertThat(preparado.fronteraPublicacion().toEpochMilli()).isGreaterThan(20_000);
        assertThat(SimulacionJob.esPublicable(preparado, 7)).isTrue();
    }

    @Test
    void soloSePuedeGuardarUnBloquePreparado() {
        AtomicReference<SimulacionJob.BloquePreparado> slot = new AtomicReference<>();
        assertThat(slot.compareAndSet(null, preparado(1, 1, 1_000, Set.of()))).isTrue();
        assertThat(slot.compareAndSet(null, preparado(2, 1, 2_000, Set.of()))).isFalse();
    }

    @Test
    void guardiaImpideDosPlanificacionesActivas() {
        AtomicBoolean activa = new AtomicBoolean();
        assertThat(activa.compareAndSet(false, true)).isTrue();
        assertThat(activa.compareAndSet(false, true)).isFalse();
    }

    @Test
    void cancelacionRelevantePorVueloInvalidaConceptualmenteElPreparado() {
        var preparado = preparado(2, 3, 35_000, Set.of("VUELO@INSTANTE"));
        assertThat(SimulacionJob.cancelacionAfecta(
                preparado, "VUELO@INSTANTE", List.of())).isTrue();
    }

    @Test
    void cancelacionDetectaVueloEnEventosFisicosAunqueNoEsteEnSolucionNueva() {
        EventoVueloDTO salida = new EventoVueloDTO();
        salida.setCodigoVuelo(661L);
        salida.setHoraSalidaUtc("2026-07-20T10:30:00Z");
        var base = preparado(2, 3, 35_000, Set.of());
        var preparado = new SimulacionJob.BloquePreparado(
                base.indiceFisico(), base.ventanaInicio(), base.ventanaFin(), base.versionPlan(),
                base.inicioCalculo(), base.finCalculo(), base.fronteraPublicacion(), List.of(salida),
                base.envios(), base.solucion(), base.metricas(), base.planificacionMs(), base.alistamientoMs(),
                base.taMs(), base.hundimientoMs(), base.taEstimadoMs(), base.clavesVuelos(),
                base.invalidado(), base.causaInvalidacion());

        assertThat(SimulacionJob.cancelacionAfecta(
                preparado, "661|2026-07-20T10:30:00Z", List.of())).isTrue();
    }

    @Test
    void cancelacionIrrelevanteNoInvalidaElPreparado() {
        var preparado = preparado(2, 3, 35_000, Set.of("OTRO@INSTANTE"));
        assertThat(SimulacionJob.cancelacionAfecta(
                preparado, "VUELO@INSTANTE", List.of())).isFalse();
    }

    @Test
    void cancelacionDespuesDeHPermiteDespachoRegistradoSoloPorBloquePreparado() {
        Instant solicitud = Instant.parse("2026-07-20T09:00:00Z");
        Instant salidaFutura = Instant.parse("2026-07-20T09:30:00Z");

        assertThat(SimulacionJob.vueloYaDespachadoAlInstante(true, salidaFutura, solicitud)).isFalse();
    }

    @Test
    void vueloQueYaDespegoMantieneElConflicto() {
        Instant solicitud = Instant.parse("2026-07-20T09:00:00Z");

        assertThat(SimulacionJob.vueloYaDespachadoAlInstante(
                true, Instant.parse("2026-07-20T08:59:59Z"), solicitud)).isTrue();
        assertThat(SimulacionJob.vueloYaDespachadoAlInstante(true, solicitud, solicitud)).isTrue();
    }

    @Test
    void vueloNoDespachadoEsCancelableAntesDeH() {
        assertThat(SimulacionJob.vueloYaDespachadoAlInstante(
                false, Instant.parse("2026-07-20T09:30:00Z"),
                Instant.parse("2026-07-20T09:00:00Z"))).isFalse();
    }

    @Test
    void versionAntiguaNuncaEsPublicable() {
        assertThat(SimulacionJob.esPublicable(preparado(2, 4, 35_000, Set.of()), 5)).isFalse();
    }

    @Test
    void preparadoMarcadoObsoletoNuncaEsPublicable() {
        var preparado = preparado(2, 5, 35_000, Set.of());
        preparado.invalidado().set(true);
        assertThat(SimulacionJob.esPublicable(preparado, 5)).isFalse();
    }

    @Test
    void dosSimulacionesNoCompartenSlotNiVersion() {
        AtomicReference<SimulacionJob.BloquePreparado> primera = new AtomicReference<>();
        AtomicReference<SimulacionJob.BloquePreparado> segunda = new AtomicReference<>();
        primera.set(preparado(2, 2, 35_000, Set.of()));
        segunda.set(preparado(2, 9, 35_000, Set.of()));
        assertThat(primera.get().versionPlan()).isEqualTo(2);
        assertThat(segunda.get().versionPlan()).isEqualTo(9);
    }

    private SimulacionJob.BloquePreparado preparado(
            long indice, long version, long frontera, Set<String> vuelos) {
        return new SimulacionJob.BloquePreparado(
                indice, LocalDateTime.of(2026, 7, 20, 8, 0),
                LocalDateTime.of(2026, 7, 20, 10, 0), version,
                Instant.ofEpochMilli(10_000), Instant.ofEpochMilli(12_000),
                Instant.ofEpochMilli(frontera), List.of(), List.of(), null, null,
                1_000, 100, 2_000, 17_500, 2_000, vuelos,
                new AtomicBoolean(false), new AtomicReference<>());
    }
}
