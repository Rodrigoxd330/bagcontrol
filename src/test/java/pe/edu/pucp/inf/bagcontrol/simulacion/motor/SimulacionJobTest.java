package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EstadoCapacidad;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoBaseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoReplanificacionEnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoVueloDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.TipoEvento;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulacionJobTest {

    @Test
    void implementacionActualAceptaKFueraDe60_120_180() {
        SimulacionJob job = crearJobPrueba(new SimulacionState("sim-k-actual"));

        assertThat(job.getK()).isEqualTo(240);
    }

    @Test
    void instrumentacionConfirmaPrimerLoteInmediatoYSegundoCalculoInmediato() {
        AeropuertoRepository repo = mock(AeropuertoRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        PlanificadorService planificador = mock(PlanificadorService.class);
        when(planificador.calcularSolucion(anyString(), any(), any(), any(), any()))
                .thenAnswer(invocacion -> {
                    Thread.sleep(5L);
                    return new SolucionRuta();
                });
        SimulacionState state = new SimulacionState("sim-instrumentada");
        SimulacionJob job = new SimulacionJob(
                state.getSimulacionId(), LocalDateTime.of(2026, 7, 20, 8, 0),
                LocalDateTime.of(2026, 7, 20, 10, 0), 60, "TABU",
                planificador, repo, mock(WebSocketPublisher.class),
                new SimulacionEventosFactory(new ConfiguracionColapsoDTO()), state,
                new ConfiguracionColapsoDTO(), new SimulacionStateMutator(state, repo), "BENCHMARK"
        );

        job.run();

        assertThat(state.getMetricasPlanificacionPorBloque()).hasSize(2);
        var primero = state.getMetricasPlanificacionPorBloque().get(0);
        var segundo = state.getMetricasPlanificacionPorBloque().get(1);
        assertThat(primero.getInicioEspera()).isNull();
        assertThat(primero.getPublicacion()).isAfterOrEqualTo(primero.getFinRealCalculo());
        assertThat(segundo.getInicioRealCalculo()).isAfterOrEqualTo(primero.getPublicacion());
        assertThat(segundo.getInicioRealCalculo().toEpochMilli() - primero.getPublicacion().toEpochMilli())
                .isLessThan(100L);
        assertThat(primero.getTaTotalMs()).isPositive();
        assertThat(primero.getK()).isEqualTo(60);
        assertThat(primero.getSaMs()).isEqualTo(28_000);
    }

    @Test
    void timestampsRealesSeRegistranUnaSolaVez() {
        SimulacionState state = new SimulacionState("sim-reloj-real");

        state.registrarInicioReal();
        Instant inicio = state.getFechaHoraInicioReal();
        state.registrarInicioReal();
        state.registrarFinReal();
        Instant fin = state.getFechaHoraFinReal();
        state.registrarFinReal();

        assertThat(state.getFechaHoraInicioReal()).isEqualTo(inicio);
        assertThat(state.getFechaHoraFinReal()).isEqualTo(fin);
        assertThat(fin).isAfterOrEqualTo(inicio);
    }

    @Test
    void capacidadExactaEsValidaYExcesoMinimoSeDetectaEnCapacidadMasUno() throws Exception {
        Aeropuerto aeropuerto = crearAeropuerto("LIM", "AMERICA");
        aeropuerto.setCapacidadAlmacen(420);
        SimulacionState state = new SimulacionState("sim-capacidad");
        state.getAeropuertosSnapshot().put("LIM", aeropuerto);
        state.getInventarioSnapshot().put("LIM", 419);
        SimulacionJob job = crearJobPrueba(state);
        Method sumar = SimulacionJob.class.getDeclaredMethod(
                "sumarInventarioYDetectarColapso", String.class, int.class, Instant.class,
                String.class, String.class, Long.class, List.class
        );
        sumar.setAccessible(true);
        List<EventoBaseDTO> eventos = new ArrayList<>();

        Optional<?> exacto = (Optional<?>) sumar.invoke(
                job, "LIM", 1, Instant.parse("2026-07-20T08:30:00Z"),
                "ENTRADA", "PED-420", null, eventos
        );
        assertThat(exacto).isEmpty();
        assertThat(state.getInventarioSnapshot().get("LIM")).isEqualTo(420);

        Optional<?> exceso = (Optional<?>) sumar.invoke(
                job, "LIM", 27, Instant.parse("2026-07-20T08:31:00Z"),
                "ENTRADA", "PED-421", null, eventos
        );
        assertThat(exceso).isPresent();
        assertThat(state.getInventarioSnapshot().get("LIM")).isEqualTo(421);
    }

    @Test
    void envioPendienteRegistraCheckInParaValidarCapacidadFisica() throws Exception {
        SimulacionState state = new SimulacionState("sim-pendiente");
        state.getInventarioSnapshot().put("LIM", 10);
        SolucionRuta solucion = new SolucionRuta();
        solucion.agregarAsignacion(crearEnvio(), null);
        SimulacionJob job = crearJobPrueba(state);
        Method registrar = SimulacionJob.class.getDeclaredMethod("registrarEnviosNuevos", SolucionRuta.class);
        registrar.setAccessible(true);

        List<?> checkIns = (List<?>) registrar.invoke(job, solucion);

        assertThat(checkIns).hasSize(1);
        assertThat(state.getInventarioSnapshot().get("LIM")).isEqualTo(10);
    }

    @Test
    void envioEnColaColapsaAlVencerSlaAunqueConserveRutaProgramada() throws Exception {
        SimulacionState state = new SimulacionState("sim-sla-cola");
        state.getAeropuertosSnapshot().put("LIM", crearAeropuerto("LIM", "AMERICA"));
        state.getAeropuertosSnapshot().put("BOG", crearAeropuerto("BOG", "AMERICA"));
        Envio envio = crearEnvio();
        SolucionRuta sinRuta = new SolucionRuta();
        sinRuta.agregarAsignacion(envio, null);
        SimulacionJob job = crearJobPrueba(state);

        Method registrar = SimulacionJob.class.getDeclaredMethod("registrarEnviosNuevos", SolucionRuta.class);
        registrar.setAccessible(true);
        registrar.invoke(job, sinRuta);
        state.getEnviosEnSeguimiento().get(envio.getIdPedido()).setItinerario(
                new Itinerario(List.of(crearVuelo()))
        );

        Method encontrar = SimulacionJob.class.getDeclaredMethod(
                "encontrarPrimerIncumplimientoSla", Instant.class
        );
        encontrar.setAccessible(true);
        Optional<?> incumplimiento = (Optional<?>) encontrar.invoke(
                job, Instant.parse("2026-07-21T08:15:00Z")
        );

        assertThat(incumplimiento).isPresent();
    }

    @Test
    void saAumentaTrasDosBloquesConsecutivosEnElUmbral() throws Exception {
        SimulacionJob job = crearJobPrueba(new SimulacionState("sim-sa-adaptativo"));
        Method ajustar = SimulacionJob.class.getDeclaredMethod("ajustarSa", long.class, long.class);
        ajustar.setAccessible(true);

        ajustar.invoke(job, 27_000L, 1L);
        assertThat(job.getSaMs()).isEqualTo(28_000);
        ajustar.invoke(job, 27_000L, 2L);
        assertThat(job.getSaMs()).isEqualTo(29_000);

        ajustar.invoke(job, 28_000L, 3L);
        ajustar.invoke(job, 8_000L, 4L);
        ajustar.invoke(job, 28_000L, 5L);
        assertThat(job.getSaMs()).isEqualTo(29_000);
        ajustar.invoke(job, 28_000L, 6L);
        assertThat(job.getSaMs()).isEqualTo(30_000);
    }

    @Test
    void acumulaSaYDiferenciaContraTaPorBloque() {
        SimulacionState state = new SimulacionState("sim-auditoria-sa");

        state.registrarTiempoBloque(4_000, 9_000, 10_000);
        state.registrarTiempoBloque(5_000, 10_000, 11_000);
        state.registrarTiempoBloque(6_000, 12_000, 11_000);

        assertThat(state.getSumaSaBloquesMs()).isEqualTo(32_000);
        assertThat(state.getSumaDiferenciaSaTaMs()).isEqualTo(1_000);
        assertThat(state.getBloquesTaMayorSa()).isEqualTo(1);
    }

    @Test
    void conservaSoloLosCuatroSnapshotsMasRecientes() {
        SimulacionState state = new SimulacionState("sim-snapshots");

        for (int i = 0; i < 6; i++) {
            state.guardarSnapshot();
        }

        assertThat(state.getHistEnviosEnSeguimiento()).hasSize(4);
        assertThat(state.getHistEnviosEnSeguimiento().keySet()).containsExactlyInAnyOrder(3L, 4L, 5L, 6L);
        assertThat(state.getHistEnviosPorVuelo()).hasSize(4);
        assertThat(state.getHistEnviosEntregados()).hasSize(4);
        assertThat(state.getHistUltimoAeropuertoPorEnvio()).hasSize(4);
    }

    @Test
    void salidaSeProcesaAntesQueCheckInEnElMismoTimestamp() throws Exception {
        Aeropuerto aeropuerto = crearAeropuerto("LIM", "AMERICA");
        aeropuerto.setCapacidadAlmacen(100);
        SimulacionState state = new SimulacionState("sim-mismo-instante");
        state.getAeropuertosSnapshot().put("LIM", aeropuerto);
        state.getAeropuertosSnapshot().put("BOG", crearAeropuerto("BOG", "AMERICA"));
        state.getInventarioSnapshot().put("LIM", 100);
        Envio existente = crearEnvio();
        existente.setIdPedido("EXISTENTE");
        state.getEnviosEnSeguimiento().put(
                existente.getIdPedido(), new RutaAsignada(existente, new Itinerario(List.of(crearVuelo())), false)
        );
        state.getUltimoAeropuertoPorEnvio().put(existente.getIdPedido(), "LIM");
        Envio nuevo = crearEnvio();
        nuevo.setIdPedido("NUEVO");
        nuevo.setFechaHora(LocalDateTime.of(2026, 7, 20, 9, 0));
        RutaAsignada checkIn = new RutaAsignada(nuevo, new Itinerario(List.of(crearVuelo())), false);
        EventoVueloDTO salida = crearEventoVuelo(TipoEvento.VUELO_DESPEGA);
        List<EventoBaseDTO> eventos = new ArrayList<>(List.of(salida));
        SimulacionJob job = crearJobPrueba(state);
        Method aplicar = SimulacionJob.class.getDeclaredMethod(
                "aplicarFisicaHasta", List.class, Instant.class, Set.class, List.class
        );
        aplicar.setAccessible(true);

        Optional<?> colapso = (Optional<?>) aplicar.invoke(job, eventos, null, Set.of(), List.of(checkIn));

        assertThat(colapso).isEmpty();
        assertThat(state.getInventarioSnapshot().get("LIM")).isEqualTo(100);
    }

    @Test
    void mismoTimestampEsEstableCuandoElEnvioExistenteTieneUbicacionFisicaRegistrada() throws Exception {
        Aeropuerto aeropuerto = crearAeropuerto("LIM", "AMERICA");
        aeropuerto.setCapacidadAlmacen(100);
        SimulacionState state = new SimulacionState("sim-mismo-instante-diagnostico");
        state.getAeropuertosSnapshot().put("LIM", aeropuerto);
        state.getAeropuertosSnapshot().put("BOG", crearAeropuerto("BOG", "AMERICA"));
        state.getInventarioSnapshot().put("LIM", 100);
        Envio existente = crearEnvio();
        existente.setIdPedido("EXISTENTE");
        state.getEnviosEnSeguimiento().put(
                existente.getIdPedido(), new RutaAsignada(existente, new Itinerario(List.of(crearVuelo())), false));
        state.getUltimoAeropuertoPorEnvio().put(existente.getIdPedido(), "LIM");
        Envio nuevo = crearEnvio();
        nuevo.setIdPedido("NUEVO");
        nuevo.setFechaHora(LocalDateTime.of(2026, 7, 20, 9, 0));
        RutaAsignada checkIn = new RutaAsignada(nuevo, new Itinerario(List.of(crearVuelo())), false);
        EventoVueloDTO salida = crearEventoVuelo(TipoEvento.VUELO_DESPEGA);
        SimulacionJob job = crearJobPrueba(state);
        Method aplicar = SimulacionJob.class.getDeclaredMethod(
                "aplicarFisicaHasta", List.class, Instant.class, Set.class, List.class);
        aplicar.setAccessible(true);

        Optional<?> colapso = (Optional<?>) aplicar.invoke(
                job, new ArrayList<>(List.of(salida)), null, Set.of(), List.of(checkIn));

        assertThat(colapso).isEmpty();
        assertThat(state.getInventarioSnapshot().get("LIM")).isEqualTo(100);
        assertThat(salida.getCodigoEnvios()).containsExactly("EXISTENTE");
    }

    @Test
    void asignacionFuturaMantieneMaletasEnAeropuertoHastaElDespegue() throws Exception {
        Aeropuerto origen = crearAeropuerto("LIM", "AMERICA");
        SimulacionState state = new SimulacionState("sim-espera-vuelo");
        state.getAeropuertosSnapshot().put("LIM", origen);
        state.getInventarioSnapshot().put("LIM", 0);
        RutaAsignada asignacion = new RutaAsignada(
                crearEnvio(), new Itinerario(List.of(crearVuelo())), false
        );
        SimulacionJob job = crearJobPrueba(state);
        Method aplicar = SimulacionJob.class.getDeclaredMethod(
                "aplicarFisicaHasta", List.class, Instant.class, Set.class, List.class
        );
        aplicar.setAccessible(true);

        aplicar.invoke(job, new ArrayList<EventoBaseDTO>(), null, Set.of(), List.of(asignacion));

        assertThat(state.getInventarioSnapshot().get("LIM")).isEqualTo(3);
    }

    @Test
    void colapsoDeCapacidadEmiteUnSoloEventoYNoFinalizaNormalmente() {
        Aeropuerto origen = crearAeropuerto("LIM", "AMERICA");
        origen.setCapacidadAlmacen(2);
        Aeropuerto destino = crearAeropuerto("BOG", "AMERICA");
        Envio envio = crearEnvio();
        SolucionRuta solucion = new SolucionRuta();
        solucion.agregarAsignacion(envio, new Itinerario(List.of(crearVuelo())));
        AeropuertoRepository repo = mock(AeropuertoRepository.class);
        when(repo.findAll()).thenReturn(List.of(origen, destino));
        PlanificadorService planificador = mock(PlanificadorService.class);
        when(planificador.calcularSolucion(anyString(), any(), any(), any(), any())).thenReturn(solucion);
        when(planificador.obtenerVuelosCanceladosEnVentana(any(), any())).thenReturn(List.of());
        WebSocketPublisher publisher = mock(WebSocketPublisher.class);
        SimulacionState state = new SimulacionState("sim-colapso-capacidad");
        SimulacionJob job = new SimulacionJob(
                "sim-colapso-capacidad", LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 20, 12, 15), 240, "TABU", planificador, repo, publisher,
                new SimulacionEventosFactory(new ConfiguracionColapsoDTO()), state,
                new ConfiguracionColapsoDTO(), new SimulacionStateMutator(state, repo), "ESTANDAR"
        );

        job.run();

        ArgumentCaptor<LoteEventosDTO> captor = ArgumentCaptor.forClass(LoteEventosDTO.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publicarLote(anyString(), captor.capture());
        List<EventoBaseDTO> publicados = captor.getAllValues().stream()
                .flatMap(lote -> lote.getEventos().stream()).toList();
        assertThat(publicados).filteredOn(EventoColapsoDTO.class::isInstance).hasSize(1);
        assertThat(publicados).noneMatch(evento -> evento.getTipo() == TipoEvento.SIMULACION_FINALIZADA);
        assertThat(state.getEstado()).isEqualTo("COLAPSADA");
        assertThat(state.getBloquesProcesados()).isEqualTo(1);
    }

    @Test
    void publicaColapsoEnDeadlineExactoDentroDelBatch() {
        Aeropuerto origen = crearAeropuerto("LIM", "AMERICA");
        Aeropuerto destino = crearAeropuerto("BOG", "AMERICA");
        Envio envio = crearEnvio();
        SolucionRuta solucion = new SolucionRuta();
        solucion.agregarAsignacion(envio, null);

        AeropuertoRepository aeropuertoRepository = mock(AeropuertoRepository.class);
        when(aeropuertoRepository.findAll()).thenReturn(List.of(origen, destino));
        PlanificadorService planificador = mock(PlanificadorService.class);
        when(planificador.calcularSolucion(anyString(), any(), any(), any(), any())).thenReturn(solucion);
        when(planificador.obtenerVuelosCanceladosEnVentana(any(), any())).thenReturn(List.of());
        WebSocketPublisher publisher = mock(WebSocketPublisher.class);
        SimulacionState state = new SimulacionState("sim-test");
        ConfiguracionColapsoDTO configuracion = new ConfiguracionColapsoDTO(0.10, 0.0, 1.0);

        SimulacionJob job = new SimulacionJob(
                "sim-test",
                LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 22, 8, 15),
                1500,
                "TABU",
                planificador,
                aeropuertoRepository,
                publisher,
                new SimulacionEventosFactory(configuracion),
                state,
                configuracion,
                new SimulacionStateMutator(state, aeropuertoRepository),
                "ESTANDAR"
        );

        job.run();

        ArgumentCaptor<LoteEventosDTO> captor = ArgumentCaptor.forClass(LoteEventosDTO.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publicarLote(anyString(), captor.capture());
        EventoColapsoDTO evento = captor.getAllValues().stream()
                .flatMap(lote -> lote.getEventos().stream())
                .filter(EventoColapsoDTO.class::isInstance)
                .map(EventoColapsoDTO.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(state.getEstado()).isEqualTo("COLAPSADA");
        assertThat(evento.getFechaHoraEvento()).isEqualTo("2026-07-21T08:15:00Z");
        assertThat(evento.getCausaPrincipal()).isEqualTo("SLA_INCUMPLIDO");
        assertThat(evento.getDetalle().getIdPedido()).isEqualTo("PED-1");
        assertThat(evento.getDetalle().getTipoSla()).isEqualTo("24H_MISMO_CONTINENTE");
        assertThat(evento.getMetricas().getDeadlinePrimerIncumplido()).isEqualTo("2026-07-21T08:15:00Z");
    }

    @Test
    void eventoPostergadoSinInventarioFisicoNoDespachaNiEntregaMaletas() throws Exception {
        verificarEventoSinInventarioFisicoNoDespachaNiEntregaMaletas(Set.of(
                "VUELO_DESPEGA|1|2026-07-20T09:00:00Z",
                "VUELO_ATERRIZA|1|2026-07-20T09:00:00Z"
        ));
    }

    @Test
    void eventoActualSinInventarioFisicoNoDespachaNiEntregaMaletas() throws Exception {
        verificarEventoSinInventarioFisicoNoDespachaNiEntregaMaletas(Set.of());
    }

    @Test
    void soloEmiteReplanificacionSiRutaAnteriorTieneVueloCancelado() throws Exception {
        AeropuertoRepository aeropuertoRepository = mock(AeropuertoRepository.class);
        when(aeropuertoRepository.findAll()).thenReturn(List.of(crearAeropuerto("LIM", "AMERICA"), crearAeropuerto("BOG", "AMERICA")));

        SimulacionState state = new SimulacionState("sim-test");
        SimulacionJob job = new SimulacionJob(
                "sim-test",
                LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 20, 12, 15),
                240,
                "TABU",
                mock(PlanificadorService.class),
                aeropuertoRepository,
                mock(WebSocketPublisher.class),
                new SimulacionEventosFactory(new ConfiguracionColapsoDTO()),
                state,
                new ConfiguracionColapsoDTO(),
                new SimulacionStateMutator(state, aeropuertoRepository),
                "ESTANDAR"
        );

        Method registrarEventosReplanificacion = SimulacionJob.class.getDeclaredMethod(
                "registrarEventosReplanificacion", SolucionRuta.class, List.class, LocalDateTime.class, int.class
        );
        registrarEventosReplanificacion.setAccessible(true);

        List<EventoBaseDTO> eventos = new ArrayList<>();
        registrarEventosReplanificacion.invoke(
                job,
                solucionConItinerario(crearVuelo(1L, false)),
                eventos,
                LocalDateTime.of(2026, 7, 20, 8, 15),
                1
        );
        registrarEventosReplanificacion.invoke(
                job,
                solucionConItinerario(crearVuelo(2L, false)),
                eventos,
                LocalDateTime.of(2026, 7, 20, 9, 15),
                2
        );

        assertThat(eventos).noneMatch(EventoReplanificacionEnvioDTO.class::isInstance);

        state.getUltimaAsignacionPorEnvio().clear();
        eventos.clear();
        registrarEventosReplanificacion.invoke(
                job,
                solucionConItinerario(crearVuelo(1L, true)),
                eventos,
                LocalDateTime.of(2026, 7, 20, 8, 15),
                1
        );
        registrarEventosReplanificacion.invoke(
                job,
                solucionConItinerario(crearVuelo(2L, false)),
                eventos,
                LocalDateTime.of(2026, 7, 20, 9, 15),
                2
        );

        assertThat(eventos)
                .filteredOn(EventoReplanificacionEnvioDTO.class::isInstance)
                .singleElement()
                .extracting(evento -> ((EventoReplanificacionEnvioDTO) evento).getMotivo())
                .isEqualTo("CAMBIO_POR_CANCELACION");
    }

    private void verificarEventoSinInventarioFisicoNoDespachaNiEntregaMaletas(
            Set<String> clavesEventosPostergadosEnBatch
    ) throws Exception {
        Aeropuerto origen = crearAeropuerto("LIM", "AMERICA");
        Aeropuerto destino = crearAeropuerto("BOG", "AMERICA");
        AeropuertoRepository aeropuertoRepository = mock(AeropuertoRepository.class);
        when(aeropuertoRepository.findAll()).thenReturn(List.of(origen, destino));

        SimulacionState state = new SimulacionState("sim-test");
        state.getAeropuertosSnapshot().put("LIM", origen);
        state.getAeropuertosSnapshot().put("BOG", destino);
        state.getInventarioSnapshot().put("LIM", 0);
        state.getInventarioSnapshot().put("BOG", 0);

        Envio envio = crearEnvio();
        VueloInstanciado vuelo = crearVuelo();
        state.getEnviosEnSeguimiento().put(
                envio.getIdPedido(), new RutaAsignada(envio, new Itinerario(List.of(vuelo)), false)
        );
        state.getUltimoAeropuertoPorEnvio().put(envio.getIdPedido(), "LIM");

        SimulacionJob job = new SimulacionJob(
                "sim-test",
                LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 20, 12, 15),
                240,
                "TABU",
                mock(PlanificadorService.class),
                aeropuertoRepository,
                mock(WebSocketPublisher.class),
                new SimulacionEventosFactory(new ConfiguracionColapsoDTO()),
                state,
                new ConfiguracionColapsoDTO(),
                new SimulacionStateMutator(state, aeropuertoRepository),
                "ESTANDAR"
        );

        EventoVueloDTO despega = crearEventoVuelo(TipoEvento.VUELO_DESPEGA);
        EventoVueloDTO aterriza = crearEventoVuelo(TipoEvento.VUELO_ATERRIZA);
        List<EventoBaseDTO> eventos = new ArrayList<>(List.of(despega, aterriza));

        Method aplicarFisicaHasta = SimulacionJob.class.getDeclaredMethod(
                "aplicarFisicaHasta", List.class, Instant.class, Set.class, List.class
        );
        aplicarFisicaHasta.setAccessible(true);
        aplicarFisicaHasta.invoke(
                job,
                eventos,
                null,
                clavesEventosPostergadosEnBatch,
                List.of()
        );

        assertThat(despega.getCantidadMaletas()).isZero();
        assertThat(aterriza.getCantidadMaletas()).isZero();
        assertThat(state.getInventarioSnapshot().get("LIM")).isZero();
        assertThat(state.getInventarioSnapshot().get("BOG")).isZero();
        assertThat(state.getEnviosEntregados()).isEmpty();
        assertThat(state.getEnviosPendientes()).extracting(Envio::getIdPedido).containsExactly("PED-1");
        assertThat(state.getEnviosEnSeguimiento().get("PED-1").getItinerario()).isNull();

        Method actualizarPendientes = SimulacionJob.class.getDeclaredMethod(
                "actualizarPendientesParaSiguienteCiclo", SolucionRuta.class
        );
        actualizarPendientes.setAccessible(true);
        actualizarPendientes.invoke(job, new SolucionRuta());

        assertThat(state.getEnviosPendientes()).extracting(Envio::getIdPedido).containsExactly("PED-1");
    }

    private Aeropuerto crearAeropuerto(String iata, String continente) {
        Aeropuerto aeropuerto = new Aeropuerto();
        aeropuerto.setCodigoIata(iata);
        aeropuerto.setContinente(continente);
        aeropuerto.setCapacidadAlmacen(100);
        return aeropuerto;
    }

    private Envio crearEnvio() {
        Envio envio = new Envio();
        envio.setIdPedido("PED-1");
        envio.setOrigenIata("LIM");
        envio.setDestinoIata("BOG");
        envio.setFechaHora(LocalDateTime.of(2026, 7, 20, 8, 15));
        envio.setCantidadMaletas(3);
        return envio;
    }

    private VueloInstanciado crearVuelo() {
        return crearVuelo(1L, false);
    }

    private SimulacionJob crearJobPrueba(SimulacionState state) {
        AeropuertoRepository repo = mock(AeropuertoRepository.class);
        return new SimulacionJob(
                state.getSimulacionId(), LocalDateTime.of(2026, 7, 20, 8, 15),
                LocalDateTime.of(2026, 7, 20, 12, 15), 240, "TABU",
                mock(PlanificadorService.class), repo, mock(WebSocketPublisher.class),
                new SimulacionEventosFactory(new ConfiguracionColapsoDTO()), state,
                new ConfiguracionColapsoDTO(), new SimulacionStateMutator(state, repo), "ESTANDAR"
        );
    }

    private VueloInstanciado crearVuelo(Long codigo, boolean cancelado) {
        Vuelo vuelo = new Vuelo("LIM", "BOG", LocalTime.of(9, 0), LocalTime.of(10, 0), 10);
        vuelo.setCodigo(codigo);
        vuelo.setEstaCancelado(cancelado);
        return new VueloInstanciado(
                vuelo,
                LocalDateTime.of(2026, 7, 20, 9, 0),
                LocalDateTime.of(2026, 7, 20, 10, 0),
                Instant.parse("2026-07-20T09:00:00Z"),
                Instant.parse("2026-07-20T10:00:00Z"),
                0
        );
    }

    private SolucionRuta solucionConItinerario(VueloInstanciado vuelo) {
        SolucionRuta solucion = new SolucionRuta();
        solucion.agregarAsignacion(crearEnvio(), new Itinerario(List.of(vuelo)));
        return solucion;
    }

    private EventoVueloDTO crearEventoVuelo(TipoEvento tipo) {
        return new EventoVueloDTO(
                tipo,
                tipo == TipoEvento.VUELO_DESPEGA ? "2026-07-20T09:00:00Z" : "2026-07-20T10:00:00Z",
                1L,
                "LIM",
                "BOG",
                EstadoCapacidad.VERDE,
                3,
                "2026-07-20T09:00",
                "2026-07-20T10:00",
                "2026-07-20T09:00:00Z",
                "2026-07-20T10:00:00Z",
                List.of("a")
        );
    }
}
