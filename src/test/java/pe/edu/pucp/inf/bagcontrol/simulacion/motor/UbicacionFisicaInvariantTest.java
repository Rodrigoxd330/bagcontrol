package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoBaseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoVueloDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EstadoCapacidad;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.TipoEvento;

import java.lang.reflect.Method;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UbicacionFisicaInvariantTest {

    @Test
    void checkInRegistraUbicacionEnOrigen() throws Exception {
        SimulacionState state = estadoBase();
        RutaAsignada asignacion = asignacion(envio("E1"), List.of(vuelo(1, "LIM", "BOG", 9, 10)));

        aplicar(state, List.of(), List.of(asignacion));

        assertThat(state.getUltimoAeropuertoPorEnvio()).containsEntry("E1", "LIM");
        assertThat(state.getInventarioSnapshot()).containsEntry("LIM", 3);
    }

    @Test
    void despegueExigeUbicacionCorrectaYDesconocidaQuedaControlada() throws Exception {
        SimulacionState state = estadoBase();
        RutaAsignada asignacion = asignacion(envio("E1"), List.of(vuelo(1, "LIM", "BOG", 9, 10)));
        state.getEnviosEnSeguimiento().put("E1", asignacion);
        state.getInventarioSnapshot().put("LIM", 3);

        aplicar(state, List.of(evento(TipoEvento.VUELO_DESPEGA, 1, "LIM", "BOG", 9, 10)), List.of());

        assertThat(state.getEnviosConUbicacionInconsistente()).contains("E1");
        assertThat(state.getInventarioSnapshot()).containsEntry("LIM", 3);
        assertThat(asignacion.getItinerario()).isNotNull();
    }

    @Test
    void envioEnOtroAeropuertoNoAborda() throws Exception {
        SimulacionState state = estadoBase();
        RutaAsignada asignacion = asignacion(envio("E1"), List.of(vuelo(1, "LIM", "BOG", 9, 10)));
        state.getEnviosEnSeguimiento().put("E1", asignacion);
        state.getUltimoAeropuertoPorEnvio().put("E1", "UIO");
        state.getInventarioSnapshot().put("LIM", 3);

        aplicar(state, List.of(evento(TipoEvento.VUELO_DESPEGA, 1, "LIM", "BOG", 9, 10)), List.of());

        assertThat(state.getInventarioSnapshot()).containsEntry("LIM", 3);
        assertThat(state.getEnviosPendientes()).extracting(Envio::getIdPedido).contains("E1");
    }

    @Test
    void aterrizajeActualizaUbicacionYEscalaLaConservaHastaSiguienteSalida() throws Exception {
        SimulacionState state = estadoBase();
        state.getAeropuertosSnapshot().put("MIA", aeropuerto("MIA"));
        state.getInventarioSnapshot().put("LIM", 3);
        state.getInventarioSnapshot().put("BOG", 0);
        Envio envio = envio("E1");
        envio.setDestinoIata("MIA");
        RutaAsignada asignacion = asignacion(envio, List.of(
                vuelo(1, "LIM", "BOG", 9, 10), vuelo(2, "BOG", "MIA", 11, 12)));
        state.getEnviosEnSeguimiento().put("E1", asignacion);
        state.getUltimoAeropuertoPorEnvio().put("E1", "LIM");

        aplicar(state, List.of(
                evento(TipoEvento.VUELO_DESPEGA, 1, "LIM", "BOG", 9, 10),
                evento(TipoEvento.VUELO_ATERRIZA, 1, "LIM", "BOG", 9, 10)), List.of());

        assertThat(state.getUltimoAeropuertoPorEnvio()).containsEntry("E1", "BOG");
        assertThat(state.getInventarioSnapshot()).containsEntry("BOG", 3);
    }

    @Test
    void cancelacionConservaUbicacionYReplanificacionParteDesdeElla() throws Exception {
        SimulacionState state = estadoBase();
        Envio envio = envio("E1");
        RutaAsignada asignacion = asignacion(envio, List.of(vuelo(1, "LIM", "BOG", 9, 10)));
        state.getEnviosEnSeguimiento().put("E1", asignacion);
        state.getUltimoAeropuertoPorEnvio().put("E1", "UIO");
        SimulacionJob job = job(state);
        Method marcar = SimulacionJob.class.getDeclaredMethod("marcarEnviosParaReplanificar", Set.class, String.class);
        marcar.setAccessible(true);
        marcar.invoke(job, Set.of("E1"), "VUELO_CANCELADO");
        Method copiar = SimulacionJob.class.getDeclaredMethod("copiarEnvioDesdeUbicacionActual", Envio.class);
        copiar.setAccessible(true);

        Envio copia = (Envio) copiar.invoke(job, envio);
        assertThat(state.getUltimoAeropuertoPorEnvio()).containsEntry("E1", "UIO");
        assertThat(copia.getOrigenIata()).isEqualTo("UIO");
        assertThat(copia.getFechaHora()).isEqualTo(envio.getFechaHora());
    }

    @Test
    void restauracionReconstruyeUbicacionInequivocaPeroNoLaAmbigua() {
        SimulacionState state = estadoBase();
        state.setTiempoActual(LocalDateTime.of(2026, 7, 20, 8, 30));
        state.getEnviosEnSeguimiento().put("E1", asignacion(envio("E1"), List.of(vuelo(1, "LIM", "BOG", 9, 10))));
        state.getEnviosEnSeguimiento().put("E2", new RutaAsignada(envio("E2"), null, false));

        state.reconstruirUbicacionesInequivocas();

        assertThat(state.getUltimoAeropuertoPorEnvio()).containsEntry("E1", "LIM").doesNotContainKey("E2");
        assertThat(state.getEnviosConUbicacionInconsistente()).contains("E2");
    }

    @Test
    void snapshotRestauraUbicacionSinInventarla() {
        SimulacionState state = estadoBase();
        state.setTiempoActual(LocalDateTime.of(2026, 7, 20, 8, 30));
        state.getEnviosEnSeguimiento().put("E1", asignacion(envio("E1"), List.of(vuelo(1, "LIM", "BOG", 9, 10))));
        state.getUltimoAeropuertoPorEnvio().put("E1", "LIM");
        state.guardarSnapshot();
        state.getUltimoAeropuertoPorEnvio().clear();

        state.restaurarSnapshot(1);

        assertThat(state.getUltimoAeropuertoPorEnvio()).containsEntry("E1", "LIM");
    }

    @Test
    void eventosDuplicadosNoDuplicanCargaNiDescarga() throws Exception {
        SimulacionState state = estadoBase();
        RutaAsignada asignacion = asignacion(envio("E1"), List.of(vuelo(1, "LIM", "BOG", 9, 10)));
        state.getEnviosEnSeguimiento().put("E1", asignacion);
        state.getUltimoAeropuertoPorEnvio().put("E1", "LIM");
        state.getInventarioSnapshot().put("LIM", 3);
        EventoVueloDTO salida = evento(TipoEvento.VUELO_DESPEGA, 1, "LIM", "BOG", 9, 10);
        EventoVueloDTO llegada = evento(TipoEvento.VUELO_ATERRIZA, 1, "LIM", "BOG", 9, 10);

        aplicar(state, List.of(salida, salida, llegada, llegada), List.of());

        assertThat(state.getInventarioSnapshot()).containsEntry("LIM", 0).containsEntry("BOG", 0);
        assertThat(state.getUltimoAeropuertoPorEnvio()).containsEntry("E1", "BOG");
    }

    @Test
    void inventarioNuncaQuedaNegativo() {
        SimulacionState state = estadoBase();
        state.getInventarioSnapshot().put("LIM", 2);
        new SimulacionStateMutator(state, mock(AeropuertoRepository.class))
                .descontarMaletasSalidaVuelo("LIM", 5, 1L, "2026-07-20T09:00:00Z", false);
        assertThat(state.getInventarioSnapshot()).containsEntry("LIM", 0);
    }

    private Optional<?> aplicar(SimulacionState state, List<EventoVueloDTO> vuelos, List<RutaAsignada> checkIns) throws Exception {
        Method metodo = SimulacionJob.class.getDeclaredMethod("aplicarFisicaHasta", List.class, Instant.class, Set.class, List.class);
        metodo.setAccessible(true);
        return (Optional<?>) metodo.invoke(job(state), new ArrayList<EventoBaseDTO>(vuelos), null, Set.of(), checkIns);
    }

    private SimulacionJob job(SimulacionState state) {
        AeropuertoRepository repo = mock(AeropuertoRepository.class);
        return new SimulacionJob(state.getSimulacionId(), LocalDateTime.of(2026, 7, 20, 8, 0),
                LocalDateTime.of(2026, 7, 21, 8, 0), 120, "TABU", mock(PlanificadorService.class), repo,
                mock(WebSocketPublisher.class), new SimulacionEventosFactory(new ConfiguracionColapsoDTO()), state,
                new ConfiguracionColapsoDTO(), new SimulacionStateMutator(state, repo), "BENCHMARK");
    }

    private SimulacionState estadoBase() {
        SimulacionState state = new SimulacionState("ubicacion");
        state.setTiempoActual(LocalDateTime.of(2026, 7, 20, 8, 0));
        for (String iata : List.of("LIM", "BOG", "UIO")) {
            state.getAeropuertosSnapshot().put(iata, aeropuerto(iata));
            state.getInventarioSnapshot().put(iata, 0);
        }
        return state;
    }

    private Aeropuerto aeropuerto(String iata) {
        Aeropuerto a = new Aeropuerto(); a.setCodigoIata(iata); a.setCapacidadAlmacen(100); return a;
    }

    private Envio envio(String id) {
        Envio e = new Envio(); e.setIdPedido(id); e.setOrigenIata("LIM"); e.setDestinoIata("BOG");
        e.setFechaHora(LocalDateTime.of(2026, 7, 20, 8, 0)); e.setCantidadMaletas(3); return e;
    }

    private RutaAsignada asignacion(Envio envio, List<VueloInstanciado> vuelos) {
        return new RutaAsignada(envio, new Itinerario(vuelos), false);
    }

    private VueloInstanciado vuelo(long codigo, String origen, String destino, int salida, int llegada) {
        Vuelo base = new Vuelo(origen, destino, LocalTime.of(salida, 0), LocalTime.of(llegada, 0), 20);
        base.setCodigo(codigo);
        return new VueloInstanciado(base, LocalDateTime.of(2026, 7, 20, salida, 0),
                LocalDateTime.of(2026, 7, 20, llegada, 0),
                Instant.parse(String.format("2026-07-20T%02d:00:00Z", salida)),
                Instant.parse(String.format("2026-07-20T%02d:00:00Z", llegada)), 0);
    }

    private EventoVueloDTO evento(TipoEvento tipo, long codigo, String origen, String destino, int salida, int llegada) {
        return new EventoVueloDTO(tipo,
                String.format("2026-07-20T%02d:00:00Z", tipo == TipoEvento.VUELO_DESPEGA ? salida : llegada),
                codigo, origen, destino, EstadoCapacidad.VERDE, 3,
                String.format("2026-07-20T%02d:00", salida), String.format("2026-07-20T%02d:00", llegada),
                String.format("2026-07-20T%02d:00:00Z", salida), String.format("2026-07-20T%02d:00:00Z", llegada),
                List.of());
    }
}
