package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulacionManagerRutaTest {

    @Test
    void muestraRutaVigenteCuandoSnapshotConservaRutaAnterior() throws Exception {
        SimulacionManager manager = new SimulacionManager(
                mock(PlanificadorService.class),
                mock(AeropuertoRepository.class),
                mock(WebSocketPublisher.class)
        );
        SimulacionState state = new SimulacionState("sim-ruta-replanificada");
        state.setFechaInicioSimulacion(LocalDateTime.of(2026, 7, 20, 8, 0));
        state.setKMinutos(120);
        Envio envio = crearEnvio();
        state.getEnviosEnSeguimiento().put(
                envio.getIdPedido(), new RutaAsignada(envio, new Itinerario(List.of(crearVuelo(10L))), false)
        );
        state.getUltimoAeropuertoPorEnvio().put(envio.getIdPedido(), "LIM");
        state.registrarEnvioEnAlmacen("LIM", envio.getIdPedido());
        state.guardarSnapshot();
        state.getEnviosEnSeguimiento().put(
                envio.getIdPedido(),
                new RutaAsignada(envio, new Itinerario(List.of(crearVuelo(20L))), false)
        );
        SimulacionJob job = mock(SimulacionJob.class);
        when(job.getState()).thenReturn(state);
        registrarJob(manager, job);

        var ruta = manager.obtenerRutaEnvio(
                state.getSimulacionId(), envio.getIdPedido(), "2026-07-20T08:30:00Z"
        );

        assertThat(ruta.getIdItinerario()).isNotBlank();
        assertThat(ruta.getEscalas()).hasSize(1);
        assertThat(ruta.getEscalas().get(0).getCodigoVuelo()).isEqualTo(20L);
        assertThat(ruta.getAeropuertoActual()).isEqualTo("LIM");
        assertThat(manager.obtenerEnviosPorAlmacen(
                state.getSimulacionId(), "LIM", "2026-07-20T08:30:00Z"
        )).singleElement().extracting(envioAlmacen -> envioAlmacen.getEnvio().getIdPedido())
                .isEqualTo(envio.getIdPedido());
    }

    @Test
    void muestraRutaVigenteAunqueTodaviaNoExistaSnapshot() throws Exception {
        SimulacionManager manager = new SimulacionManager(
                mock(PlanificadorService.class),
                mock(AeropuertoRepository.class),
                mock(WebSocketPublisher.class)
        );
        SimulacionState state = new SimulacionState("sim-ruta-replanificada");
        state.setFechaInicioSimulacion(LocalDateTime.of(2026, 7, 20, 8, 0));
        state.setKMinutos(120);
        Envio envio = crearEnvio();
        state.getEnviosEnSeguimiento().put(
                envio.getIdPedido(), new RutaAsignada(envio, new Itinerario(List.of(crearVuelo(30L))), false)
        );
        state.getUltimoAeropuertoPorEnvio().put(envio.getIdPedido(), "LIM");
        SimulacionJob job = mock(SimulacionJob.class);
        when(job.getState()).thenReturn(state);
        registrarJob(manager, job);

        var ruta = manager.obtenerRutaEnvio(
                state.getSimulacionId(), envio.getIdPedido(), "2026-07-20T08:30:00Z"
        );

        assertThat(ruta.getEscalas()).singleElement()
                .extracting(escala -> escala.getCodigoVuelo())
                .isEqualTo(30L);
        assertThat(ruta.getAeropuertoActual()).isEqualTo("LIM");
    }

    @SuppressWarnings("unchecked")
    private void registrarJob(SimulacionManager manager, SimulacionJob job) throws Exception {
        Field field = SimulacionManager.class.getDeclaredField("trabajosActivos");
        field.setAccessible(true);
        ConcurrentHashMap<String, SimulacionJob> trabajos =
                (ConcurrentHashMap<String, SimulacionJob>) field.get(manager);
        trabajos.put("sim-ruta-replanificada", job);
    }

    private Envio crearEnvio() {
        Envio envio = new Envio();
        envio.setIdPedido("PED-REPLANIFICADO");
        envio.setOrigenIata("LIM");
        envio.setDestinoIata("BOG");
        envio.setFechaHora(LocalDateTime.of(2026, 7, 20, 8, 0));
        envio.setCantidadMaletas(2);
        return envio;
    }

    private VueloInstanciado crearVuelo(Long codigo) {
        Vuelo vuelo = new Vuelo("LIM", "BOG", LocalTime.of(10, 0), LocalTime.of(11, 0), 100);
        vuelo.setCodigo(codigo);
        return new VueloInstanciado(
                vuelo,
                LocalDateTime.of(2026, 7, 20, 10, 0),
                LocalDateTime.of(2026, 7, 20, 11, 0),
                Instant.parse("2026-07-20T10:00:00Z"),
                Instant.parse("2026-07-20T11:00:00Z"),
                0
        );
    }
}
