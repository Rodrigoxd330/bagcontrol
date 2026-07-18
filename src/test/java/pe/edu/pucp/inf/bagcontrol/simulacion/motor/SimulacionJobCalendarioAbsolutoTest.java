package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulacionJobCalendarioAbsolutoTest {

    private static final int F = SimulacionJob.SA_INICIAL_MS;

    @Test
    void primerBloqueEsInmediatoYLosControlesNoCuentanComoBloquesFisicos() {
        var calendario = new SimulacionJob.CalendarioPublicaciones();

        assertThat(calendario.getIndiceBloqueFisico()).isZero();
        var primero = calendario.registrarPublicacion(1_000L, 990L, F);

        assertThat(primero.indiceBloqueFisico()).isEqualTo(1);
        assertThat(primero.origenPublicacionesMs()).isEqualTo(1_000L);
        assertThat(primero.fronteraProgramadaMs()).isEqualTo(1_000L);
        assertThat(primero.atrasoMs()).isZero();
    }

    @Test
    void segundoYTercerBloqueUsanOrigenMasUnoYDosIntervalos() {
        var calendario = calendarioConPrimerBloque(10_000L);

        var segundo = calendario.registrarPublicacion(45_000L, 12_000L, F);
        var tercero = calendario.registrarPublicacion(80_000L, 47_000L, F);

        assertThat(segundo.fronteraProgramadaMs()).isEqualTo(10_000L + F);
        assertThat(tercero.fronteraProgramadaMs()).isEqualTo(10_000L + 2L * F);
    }

    @Test
    void sesentaBloquesMantienenFronterasAbsolutasSinDeriva() {
        long origen = 5_000L;
        var calendario = calendarioConPrimerBloque(origen);

        SimulacionJob.RegistroPublicacion ultimo = null;
        for (int n = 2; n <= 60; n++) {
            long frontera = origen + (n - 1L) * F;
            ultimo = calendario.registrarPublicacion(frontera, frontera - 2_000L, F);
        }

        assertThat(ultimo).isNotNull();
        assertThat(ultimo.indiceBloqueFisico()).isEqualTo(60);
        assertThat(ultimo.fronteraProgramadaMs()).isEqualTo(origen + 59L * F);
        assertThat(ultimo.derivaAcumuladaMs()).isZero();
    }

    @Test
    void costosPequenosDePublicacionNoMuevenFronterasFuturas() {
        var calendario = calendarioConPrimerBloque(1_000L);

        calendario.registrarPublicacion(36_007L, 30_000L, F);

        assertThat(calendario.getFronteraProgramadaMs()).isEqualTo(71_000L);
    }

    @Test
    void bloqueTempranoRegistraAdelantoYConservaLaFrontera() {
        var calendario = calendarioConPrimerBloque(1_000L);

        var segundo = calendario.registrarPublicacion(36_000L, 30_000L, F);

        assertThat(segundo.adelantoAntesEsperaMs()).isEqualTo(6_000L);
        assertThat(segundo.atrasoMs()).isZero();
    }

    @Test
    void bloqueTardioSePublicaYLaSiguienteFronteraConservaElOrigen() {
        var calendario = calendarioConPrimerBloque(1_000L);

        var segundo = calendario.registrarPublicacion(38_000L, 38_000L, F);

        assertThat(segundo.atrasoMs()).isEqualTo(2_000L);
        assertThat(segundo.fronterasIncumplidas()).isEqualTo(1);
        assertThat(calendario.getFronteraProgramadaMs()).isEqualTo(71_000L);
    }

    @Test
    void cancelacionOControlNoDesplazanIndiceNiFrontera() {
        var calendario = calendarioConPrimerBloque(1_000L);
        long indiceAntes = calendario.getIndiceBloqueFisico();
        long fronteraAntes = calendario.getFronteraProgramadaMs();

        // Los mensajes de control no invocan registrarPublicacion.

        assertThat(calendario.getIndiceBloqueFisico()).isEqualTo(indiceAntes);
        assertThat(calendario.getFronteraProgramadaMs()).isEqualTo(fronteraAntes);
    }

    @Test
    void pausaDesplazaOrigenYFronterasPorSuDuracion() {
        var calendario = calendarioConPrimerBloque(1_000L);

        calendario.iniciarPausa(10_000L);
        long duracion = calendario.finalizarPausa(15_000L);

        assertThat(duracion).isEqualTo(5_000L);
        assertThat(calendario.getOrigenPublicacionesMs()).isEqualTo(6_000L);
        assertThat(calendario.getFronteraProgramadaMs()).isEqualTo(41_000L);
        assertThat(calendario.getTiempoPausadoAcumuladoMs()).isEqualTo(5_000L);
    }

    @Test
    void pausasRepetidasSeAcumulanUnaSolaVezYNoGeneranRafaga() {
        var calendario = calendarioConPrimerBloque(1_000L);
        calendario.iniciarPausa(10_000L);
        calendario.iniciarPausa(11_000L);
        calendario.finalizarPausa(15_000L);

        var segundo = calendario.registrarPublicacion(41_000L, 20_000L, F);

        assertThat(segundo.atrasoMs()).isZero();
        assertThat(segundo.fronteraProgramadaMs()).isEqualTo(41_000L);
    }

    @Test
    void dosSimulacionesNoCompartenCalendario() {
        var primera = calendarioConPrimerBloque(1_000L);
        var segunda = calendarioConPrimerBloque(9_000L);

        primera.registrarPublicacion(36_000L, 20_000L, F);

        assertThat(primera.getFronteraProgramadaMs()).isEqualTo(71_000L);
        assertThat(segunda.getFronteraProgramadaMs()).isEqualTo(44_000L);
    }

    @Test
    void frecuenciaInicialPermaneceEnTreintaYCincoSegundos() {
        assertThat(F).isEqualTo(35_000);
    }

    @Test
    void frecuenciaAdaptativaSeAplicaSoloAlSiguienteIntervaloSinReiniciarOrigen() {
        var calendario = calendarioConPrimerBloque(1_000L);
        var segundo = calendario.registrarPublicacion(36_000L, 20_000L, 36_000);
        var tercero = calendario.registrarPublicacion(72_000L, 50_000L, 40_000);

        assertThat(segundo.frecuenciaIntervaloMs()).isEqualTo(35_000);
        assertThat(tercero.frecuenciaIntervaloMs()).isEqualTo(36_000);
        assertThat(tercero.fronteraProgramadaMs()).isEqualTo(72_000L);
        assertThat(calendario.getOrigenPublicacionesMs()).isEqualTo(1_000L);
        assertThat(calendario.getFronteraProgramadaMs()).isEqualTo(112_000L);
    }

    @Test
    void frecuenciaMaximaDeCuarentaSegundosConservaLaCadenaProgramada() {
        var calendario = calendarioConPrimerBloque(1_000L);
        calendario.registrarPublicacion(36_000L, 20_000L, SimulacionJob.SA_MAXIMO_MS);

        assertThat(calendario.getFronteraProgramadaMs()).isEqualTo(76_000L);
        assertThat(calendario.getOrigenPublicacionesMs()).isEqualTo(1_000L);
    }

    @Test
    void atrasoNoSeConvierteEnOrigenNuevo() {
        var calendario = calendarioConPrimerBloque(1_000L);
        calendario.registrarPublicacion(41_000L, 41_000L, F);
        var tercero = calendario.registrarPublicacion(71_000L, 60_000L, F);

        assertThat(tercero.fronteraProgramadaMs()).isEqualTo(71_000L);
        assertThat(tercero.atrasoMs()).isZero();
    }

    @Test
    void cuentaCadaFronteraIncumplidaSinPerderBloques() {
        var calendario = calendarioConPrimerBloque(1_000L);
        calendario.registrarPublicacion(36_001L, 36_001L, F);
        calendario.registrarPublicacion(71_002L, 71_002L, F);

        assertThat(calendario.getFronterasIncumplidas()).isEqualTo(2);
        assertThat(calendario.getIndiceBloqueFisico()).isEqualTo(3);
    }

    @Test
    void reanudarSinPausaNoAjustaElCalendario() {
        var calendario = calendarioConPrimerBloque(1_000L);

        assertThat(calendario.finalizarPausa(20_000L)).isZero();
        assertThat(calendario.getOrigenPublicacionesMs()).isEqualTo(1_000L);
        assertThat(calendario.getFronteraProgramadaMs()).isEqualTo(36_000L);
    }

    @Test
    void registroSecuencialImpideIndicesDuplicadosOSuperpuestos() {
        var calendario = calendarioConPrimerBloque(1_000L);
        var segundo = calendario.registrarPublicacion(36_000L, 20_000L, F);
        var tercero = calendario.registrarPublicacion(71_000L, 50_000L, F);

        assertThat(segundo.indiceBloqueFisico()).isEqualTo(2);
        assertThat(tercero.indiceBloqueFisico()).isEqualTo(3);
        assertThat(calendario.getIndiceBloqueFisico()).isEqualTo(3);
    }

    @Test
    void detencionDuranteEsperaInterrumpeSinPublicarElLotePendiente() throws Exception {
        AeropuertoRepository repo = mock(AeropuertoRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        PlanificadorService planificador = mock(PlanificadorService.class);
        when(planificador.calcularSolucion(anyString(), any(), any(), any(), any()))
                .thenReturn(new SolucionRuta());
        SimulacionState state = new SimulacionState("sim-detencion-calendario");
        SimulacionJob job = new SimulacionJob(
                state.getSimulacionId(), LocalDateTime.of(2026, 7, 20, 8, 0),
                LocalDateTime.of(2026, 7, 20, 10, 0), 60, "TABU", planificador, repo,
                mock(WebSocketPublisher.class), new SimulacionEventosFactory(new ConfiguracionColapsoDTO()),
                state, new ConfiguracionColapsoDTO(), new SimulacionStateMutator(state, repo), "1");
        Thread hilo = new Thread(job);
        job.asignarHilo(hilo);
        hilo.start();
        long limite = System.currentTimeMillis() + 2_000L;
        while (state.getBloquesProcesados() < 1 && System.currentTimeMillis() < limite) {
            Thread.sleep(10L);
        }

        job.detener();
        hilo.join(1_000L);

        assertThat(hilo.isAlive()).isFalse();
        assertThat(state.getBloquesProcesados()).isEqualTo(1);
        assertThat(state.getEstado()).isEqualTo("DETENIDA");
    }

    private SimulacionJob.CalendarioPublicaciones calendarioConPrimerBloque(long origen) {
        var calendario = new SimulacionJob.CalendarioPublicaciones();
        calendario.registrarPublicacion(origen, origen, F);
        return calendario;
    }
}
