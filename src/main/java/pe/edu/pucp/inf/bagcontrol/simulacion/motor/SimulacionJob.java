package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Getter;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.auth.UsuarioSesion;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.DetalleColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoAeropuertoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoBaseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoEstadoSimulacionDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoReplanificacionEnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoVueloDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EstadoCapacidad;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.TipoEvento;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimulacionJob implements Runnable {

    @Getter
    private final int saMs;

    private final String simulacionId;
    private final LocalDateTime horaInicio;
    private final LocalDateTime horaFin;
    @Getter
    private final int k;
    @Getter
    private final String algoritmo;

    private final AtomicBoolean pausada = new AtomicBoolean(false);
    private final AtomicBoolean detenida = new AtomicBoolean(false);
    private final AtomicBoolean colapsoPublicado = new AtomicBoolean(false);
    @Getter
    private final LocalDateTime fechaCreacion = LocalDateTime.now();

    private final PlanificadorService planificadorService;
    private final WebSocketPublisher webSocketPublisher;
    private final ConfiguracionColapsoDTO configuracionColapsoDTO;
    private final SimulacionEventosFactory simulacionEventosFactory;
    @Getter
    private final SimulacionState state;
    private final SimulacionStateMutator simulacionStateMutator;
    private final Map<String, Integer> maletasDespachadasPorVuelo = new LinkedHashMap<>();
    private final Map<String, Set<String>> enviosDespachadosPorVuelo = new LinkedHashMap<>();
    private final Set<String> enviosConCheckIn = new HashSet<>();
    private long tiempoUltimoLoteMs = 0L;
    private long inicioJobMs = 0L;
    private static final int MAX_EVENTOS_REPLANIFICACION_POR_BLOQUE = 50;
    private static final String MODO_OPERACION_DIA = "0";
    @Getter
    private final String modo;
    @Getter
    private final UsuarioSesion propietario;
    private final SimulacionContextoDatos contextoDatos;
    private final Set<String> enviosCrudExcluidos;

    private volatile Thread hilo;

    public SimulacionJob(
            String simulacionId, LocalDateTime horaInicio, LocalDateTime horaFin, int k, String algoritmo,
            PlanificadorService planificadorService, AeropuertoRepository aeropuertoRepository,
            WebSocketPublisher webSocketPublisher, SimulacionEventosFactory simulacionEventosFactory,
            SimulacionState state, ConfiguracionColapsoDTO configuracionColapsoDTO,
            SimulacionStateMutator simulacionStateMutator, String modo, Set<String> enviosCrudExcluidos
    ) {
        this(
                simulacionId,
                horaInicio,
                horaFin,
                k,
                algoritmo,
                planificadorService,
                aeropuertoRepository,
                webSocketPublisher,
                simulacionEventosFactory,
                state,
                configuracionColapsoDTO,
                simulacionStateMutator,
                modo,
                enviosCrudExcluidos,
                null,
                null
        );
    }

    public SimulacionJob(
            String simulacionId, LocalDateTime horaInicio, LocalDateTime horaFin, int k, String algoritmo,
            PlanificadorService planificadorService, AeropuertoRepository aeropuertoRepository,
            WebSocketPublisher webSocketPublisher, SimulacionEventosFactory simulacionEventosFactory,
            SimulacionState state, ConfiguracionColapsoDTO configuracionColapsoDTO,
            SimulacionStateMutator simulacionStateMutator, String modo, Set<String> enviosCrudExcluidos,
            SimulacionContextoDatos contextoDatos, UsuarioSesion propietario
    ) {
        this.simulacionId = simulacionId;
        this.horaInicio = horaInicio;
        this.horaFin = horaFin;
        this.k = k;
        this.algoritmo = algoritmo;
        this.planificadorService = planificadorService;
        this.webSocketPublisher = webSocketPublisher;
        this.simulacionEventosFactory = simulacionEventosFactory;
        this.state = state;
        // En operación día a día (modo="0"), el saMs es proporcional a K
        // para que factorAceleracion = K*60/SaS = 1 → tiempo real
        // (1s real = 1s sim, un vuelo de 2h tarda 2h reales)
        this.saMs = (modo != null && "0".equals(modo)) ? k * 60 * 1000 : 90_000;
        this.configuracionColapsoDTO = configuracionColapsoDTO;
        this.simulacionStateMutator = simulacionStateMutator;
        this.modo = modo;
        this.enviosCrudExcluidos = enviosCrudExcluidos == null ? Set.of() : Set.copyOf(enviosCrudExcluidos);
        this.contextoDatos = contextoDatos;
        this.propietario = propietario;
    }

    public void asignarHilo(Thread hilo) {
        this.hilo = hilo;
    }

    @Override
    public void run() {
        state.registrarInicioReal();
        state.setEstado("EN_PROCESO");
        long inicioProceso = System.currentTimeMillis();
        inicioJobMs = inicioProceso;
        System.out.println("[BACK-SIM-TIME] job iniciado id=" + simulacionId
                + " ts=" + Instant.now());
        try {
            ejecutarSimulacion();
        } catch (SimulacionDetenidaException e) {
            state.setEstado("DETENIDA");
            state.registrarFinReal();
            publicarControl(TipoEvento.SIMULACION_DETENIDA);
        } catch (Exception e) {
            System.err.println("Error en la simulacion " + simulacionId + ": " + e.getMessage());
            e.printStackTrace();
            state.setEstado("ERROR");
            state.registrarFinReal();
            publicarControl(TipoEvento.ERROR);
        } finally {
            long totalMs = System.currentTimeMillis() - inicioProceso;
            String prefijo = esOperacionDia() ? "[OPERACION-DIA-PERFORMANCE]" : "[SIM5D-PERFORMANCE]";
            System.out.println(prefijo + " simulacionId=" + simulacionId
                    + " tiempoTotalMs=" + totalMs
                    + " bloquesProcesados=" + state.getBloquesProcesados()
                    + " saltoAlgoritmoMinutos=" + k
                    + " saltoConsumoDatosMinutos=" + k);
        }
    }

    private void ejecutarSimulacion() {
        List<Aeropuerto> aeropuertosIniciales = simulacionStateMutator.inicializarAeropuertos();
        state.setTiempoActual(horaInicio);
        if (esOperacionDia()) {
            System.out.println("[OPERACION-DIA] modo=OPERACION_DIA");
            System.out.println("[OPERACION-DIA] enviosIniciales=0");
            System.out.println("[OPERACION-DIA] usaZip=false");
            System.out.println("[OPERACION-DIA] vuelosBase=" + (contextoDatos != null
                    ? contextoDatos.vuelos().size()
                    : planificadorService.contarVuelosBase()));
            System.out.println("[OPERACION-DIA] aeropuertos=" + aeropuertosIniciales.size());
            System.out.println("[OPERACION-DIA] horizonteHoras=" + java.time.Duration.between(horaInicio, horaFin).toHours());
        } else {
            planificadorService.precargarEnvios(horaInicio);
            System.out.println("[SIMULACION-CONFIG] usaZip=true modo=" + (horaFin == null ? "COLAPSO" : "SIM5D"));
        }
        publicarControl(TipoEvento.SIMULACION_INICIADA);
        System.out.println("[BACK-SIM-TIME] tiempo total hasta primer evento id=" + simulacionId
                + " elapsedMs=" + (System.currentTimeMillis() - inicioJobMs));

        LocalDateTime tiempoFin = horaFin == null ? LocalDateTime.MAX : horaFin;
        Map<String, EventoVueloDTO> eventosVueloPostergados = new java.util.LinkedHashMap<>();
        publicarConfiguracionRendimiento();

        while (state.getTiempoActual().isBefore(tiempoFin)) {
            // --- FASE 1: INICIO DE MEDICIÓN DE TA ---
            long inicioCronometroTa = System.currentTimeMillis();
            long memoriaInicioBloque = memoriaUsada();
            long gcCountInicio = gcCount();
            long gcTimeInicio = gcTimeMs();
            boolean esPrimerBloque = state.getBloquesProcesados() == 0;

            System.out.println("╔══════════════════════════════════════════════════════════════╗");
            System.out.printf("║ BATCH #%d | Ciclo=%d | Ventana=%s -> %s%n",
                    state.getBloquesProcesados() + 1, state.getCicloActual() + 1,
                    state.getTiempoActual(), state.getTiempoActual().plusMinutes(k));

            if (esPrimerBloque) {
                System.out.println("[BACK-SIM-TIME] primer bloque inicio id=" + simulacionId
                        + " ts=" + Instant.now());
            }

            verificarDetencion();
            esperarSiPausadaODetenida();

            LocalDateTime ventanaInicio = state.getTiempoActual();
            LocalDateTime ventanaFin = ventanaInicio.plusMinutes(k); // K determina el salto simulado
            if (ventanaFin.isAfter(tiempoFin)) {ventanaFin = tiempoFin;}

            Instant ventanaFinUtc = ventanaFin.toInstant(ZoneOffset.UTC);
            int ciclo = state.getCicloActual() + 1;
            state.setCicloActual(ciclo);

            // --- FASE 2: EXTRACCIÓN DE CONTEXTO ---
            long inicioContexto = System.currentTimeMillis();
            List<EventoBaseDTO> eventosBatch = new ArrayList<>();
            Set<String> clavesEventosPostergadosEnBatch = new HashSet<>();
            extraerEventosVueloPostergados(
                    ventanaFinUtc, eventosBatch, eventosVueloPostergados, clavesEventosPostergadosEnBatch
            );
            List<Envio> enviosOperacionDia = esOperacionDia()
                    ? planificadorService.obtenerEnviosOperacionDiaEnVentana(ventanaInicio, ventanaFin, enviosCrudExcluidos)
                    : List.of();
            if (debeSaltarPlanificacionOperacionDia(enviosOperacionDia, eventosBatch, eventosVueloPostergados)) {
                procesarBloqueOperacionSinEnvios(ventanaInicio, ventanaFin, inicioCronometroTa);
                continue;
            }
            agregarEventosVuelosCancelados(ventanaInicio, ventanaFin, eventosBatch);
            long finContexto = System.currentTimeMillis();

            Map<String, Integer> inventarioReservado = PlanificadorUtils.construirInventarioReservado(
                    state.getEnviosEnSeguimiento(),
                    state.getEnviosEntregados(),
                    state.getInventarioSnapshot(),
                    state.getTiempoActual().toInstant(ZoneOffset.UTC)
            );

            // --- FASE 3: PLANIFICACIÓN (el paso más lento) ---
            long inicioPlanificacion = System.currentTimeMillis();
            SolucionRuta solucion = calcularSolucion(ventanaInicio, ventanaFin, inventarioReservado, enviosOperacionDia);
            long finPlanificacion = System.currentTimeMillis();
            preservarAsignacionesVigentes(solucion);
            state.setSolucionActual(solucion);
            registrarEventosReplanificacion(solucion, eventosBatch, ventanaInicio, ciclo);

            long planMs = finPlanificacion - inicioPlanificacion;
            long contextoMs = finContexto - inicioContexto;

            System.out.printf("║ [PLANIFICACION] envios=%d | fitness=%.2f | planMs=%d%n",
                    solucion.getAsignaciones().size(), solucion.getFitness(), planMs);

            // --- FASE 4: MUTACIÓN FÍSICA E INDEXACIÓN DEL ESTADO ---
            long inicioPostProc = System.currentTimeMillis();
            List<RutaAsignada> checkInsPendientes = registrarEnviosNuevos(solucion);

            // --- FASE 5: GENERACIÓN Y ORDENAMIENTO DE EVENTOS EN LA VENTANA ---
            long inicioGeneracionEventos = System.currentTimeMillis();
            SimulacionEventosFactory.ResultadoEventosVuelo eventosVuelos =
                    simulacionEventosFactory.generarEventosVuelo(solucion, ventanaFinUtc);
            eventosBatch.addAll(eventosVuelos.actuales());
            agregarEventosVueloPostergados(eventosVuelos.futuros(), eventosVueloPostergados);
            eventosBatch.sort(comparadorEventos());
            long tiempoGeneracionEventosMs = System.currentTimeMillis() - inicioGeneracionEventos;

            // --- FASE 6: CÁLCULO DE SLA Y COLAPSOS ---
            IncumplimientoSla incumplimiento = encontrarPrimerIncumplimientoSla(ventanaFinUtc).orElse(null);
            Instant instanteColapso = incumplimiento != null ? incumplimiento.deadline() : null;

            Optional<ColapsoCapacidad> colapsoCapacidad = aplicarFisicaHasta(
                    eventosBatch, instanteColapso, clavesEventosPostergadosEnBatch, checkInsPendientes
            );
            if (colapsoCapacidad.isPresent()) {
                instanteColapso = colapsoCapacidad.get().instante();
                registrarColapsoCapacidad(
                        ciclo, ventanaInicio, ventanaFin, solucion, colapsoCapacidad.get(), eventosBatch
                );
            }
            marcarEnviosEntregadosHasta(
                    instanteColapso != null ? instanteColapso : ventanaFinUtc,
                    eventosBatch
            );
            agregarAlertasAeropuertosSaturados(eventosBatch);
            eventosBatch.sort(comparadorEventos());

            simulacionStateMutator.indexarEnviosPorVuelo(enviosDespachadosPorVuelo);

            if (incumplimiento != null && colapsoCapacidad.isEmpty()) {
                registrarColapsoSla(ciclo, ventanaInicio, ventanaFin, solucion, incumplimiento, eventosBatch);
            }

            consolidarEventosVuelo(eventosBatch);

            List<EnvioDTO> enviosBatch = solucion.getAsignaciones().stream().map(e -> new EnvioDTO(e.getEnvio().getIdPedido(),
                    e.getEnvio().getOrigenIata(),e.getEnvio().getDestinoIata(),e.getEnvio().getFechaHora().toString(),
                    e.getEnvio().getCantidadMaletas(),e.getEnvio().getIdCliente())).toList();

            // --- FASE 7: COLAPSO ---
            if (incumplimiento != null || colapsoCapacidad.isPresent()) {
                publicarLote(eventosBatch,enviosBatch, ventanaInicio.toInstant(ZoneOffset.UTC), ventanaFinUtc);
                state.guardarSnapshot();
                state.setBloquesProcesados(state.getBloquesProcesados() + 1);
                publicarMetricasCapacidad(solucion);
                state.setTiempoActual(LocalDateTime.ofInstant(instanteColapso, ZoneOffset.UTC));
                break;
            }

            actualizarPendientesParaSiguienteCiclo(solucion);

            long finPostProc = System.currentTimeMillis();
            long postProcMs = finPostProc - inicioPostProc;

            // --- FASE 8: FIN DE TA Y COMPENSACIÓN DE TIEMPO (SA - TA) ---
            long taCalculadoMs = System.currentTimeMillis() - inicioCronometroTa;
            this.tiempoUltimoLoteMs = taCalculadoMs;
            if (esSimulacionCincoDias()) {
                long bloque = state.getBloquesProcesados() + 1L;
                int maletas = solucion.getAsignaciones().stream()
                        .mapToInt(asignacion -> asignacion.getEnvio().getCantidadMaletas()).sum();
                System.out.println("[SIM5D-PERF] bloque=" + bloque
                        + " envios=" + solucion.getAsignaciones().size()
                        + " maletas=" + maletas
                        + " rutas=" + solucion.getAsignaciones().stream()
                                .filter(asignacion -> asignacion.getItinerario() != null).count()
                        + " tiempoGeneracionEventosMs=" + tiempoGeneracionEventosMs
                        + " eventosGenerados=" + eventosBatch.size()
                        + " tiempoPostProcesamientoMs=" + postProcMs
                        + " tiempoTotalPlanificacionMs=" + taCalculadoMs
                        + " memoriaAntesBytes=" + memoriaInicioBloque
                        + " memoriaDespuesBytes=" + memoriaUsada()
                        + " gcCollections=" + Math.max(0L, gcCount() - gcCountInicio)
                        + " gcTimeMs=" + Math.max(0L, gcTimeMs() - gcTimeInicio));
            }

            state.setTiempoActual(ventanaFin);

            boolean esPrimerLote = state.getBloquesProcesados() == 0;
            long publicacionProgramadaMs = inicioJobMs
                    + ((long) state.getBloquesProcesados() + 1L) * saMs;
            if (esSimulacionCincoDias()) {
                esperarHasta(publicacionProgramadaMs);
            } else if (!esPrimerLote && state.getTiempoActual().isBefore(tiempoFin)) {
                esperarConControl();
            }

            // --- FASE 9: ENVÍO DE DATOS A FRONTEND ---
            publicarLote(eventosBatch,enviosBatch, ventanaInicio.toInstant(ZoneOffset.UTC), ventanaFinUtc);
            long publicacionRealMs = System.currentTimeMillis();
            long retrasoMs = Math.max(0L, publicacionRealMs - publicacionProgramadaMs);
            if (esSimulacionCincoDias()) {
                System.out.println("[SIM5D-SCHEDULE] bloque=" + (state.getBloquesProcesados() + 1)
                        + " inicioPlanificacion=" + Instant.ofEpochMilli(inicioCronometroTa)
                        + " finPlanificacion=" + Instant.ofEpochMilli(inicioCronometroTa + taCalculadoMs)
                        + " publicacionProgramada=" + Instant.ofEpochMilli(publicacionProgramadaMs)
                        + " publicacionReal=" + Instant.ofEpochMilli(publicacionRealMs)
                        + " taMs=" + taCalculadoMs
                        + " saMs=" + saMs
                        + " retrasoMs=" + retrasoMs
                        + " dentroDeMargen=" + (taCalculadoMs <= saMs));
            }
            if (esPrimerLote) {
                System.out.println("[BACK-SIM-TIME] primer lote listo/enviado id=" + simulacionId
                        + " eventos=" + eventosBatch.size()
                        + " elapsedMs=" + (System.currentTimeMillis() - inicioJobMs));
            }

            // --- RESUMEN DEL BATCH ---
            System.out.printf("║ [TIMING] contexto=%dms | planificacion=%dms | postProc=%dms | TOTAL=%dms | sa=%dms%n",
                    contextoMs, planMs, postProcMs, taCalculadoMs, saMs);
            System.out.printf("║ [LOTE-ENVIADO] numero=%d | eventos=%d | envios=%d | ventana=%s -> %s%n",
                    state.getUltimoLoteEmitidoNumero().get(), eventosBatch.size(),
                    solucion.getAsignaciones().size(), ventanaInicio, ventanaFin);
            System.out.println("╚══════════════════════════════════════════════════════════════╝");

            state.guardarSnapshot();
            state.setBloquesProcesados(state.getBloquesProcesados() + 1);
            publicarMetricasCapacidad(solucion);
        }

        if (!esTerminal()) {
            state.setEstado("FINALIZADA");
            state.registrarFinReal();
            publicarControl(TipoEvento.SIMULACION_FINALIZADA);
        }
    }

    private List<RutaAsignada> registrarEnviosNuevos(SolucionRuta solucion) {
        List<RutaAsignada> checkIns = new ArrayList<>();
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            Envio envio = asignacion.getEnvio();
            state.getEnviosEnSeguimiento().merge(
                    envio.getIdPedido(),
                    asignacion,
                    (anterior, nueva) -> nueva.getItinerario() != null ? nueva : anterior
            );
            state.getEnviosRegistrados().add(envio.getIdPedido());
            state.getUltimoAeropuertoPorEnvio().putIfAbsent(envio.getIdPedido(), envio.getOrigenIata());
            if (asignacion.getItinerario() == null) {
                System.out.println("[SIM5D-PENDING-CAPACITY-CHECK] envioId=" + envio.getIdPedido()
                        + " pendiente=true tieneMovimientosRegistrados=false"
                        + " cantidadMovimientosResiduales=0 consumeCapacidad=false");
                continue;
            }
            if (enviosConCheckIn.add(envio.getIdPedido())) {
                checkIns.add(asignacion);
            }
        }
        return checkIns;
    }

    private void registrarEventosReplanificacion(
            SolucionRuta solucion,
            List<EventoBaseDTO> eventosBatch,
            LocalDateTime ventanaInicio,
            int ciclo
    ) {
        int cambiosDetectados = 0;
        int eventosEmitidos = 0;
        Instant horaEvento = ventanaInicio.toInstant(ZoneOffset.UTC);

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            AsignacionResumen actual = crearResumenAsignacion(asignacion, horaEvento, ciclo);
            AsignacionResumen anterior = state.getUltimaAsignacionPorEnvio().get(actual.getIdPedido());

            if (anterior == null) {
                state.getUltimaAsignacionPorEnvio().put(actual.getIdPedido(), actual);
                continue;
            }

            if (!cambioAsignacion(anterior, actual)) {
                state.getUltimaAsignacionPorEnvio().put(actual.getIdPedido(), actual);
                continue;
            }

            if (!esReplanificacionPorCancelacion(anterior, actual)) {
                state.getUltimaAsignacionPorEnvio().put(actual.getIdPedido(), actual);
                continue;
            }

            cambiosDetectados++;
            if (eventosEmitidos < MAX_EVENTOS_REPLANIFICACION_POR_BLOQUE) {
                String motivo = "CAMBIO_POR_CANCELACION";
                eventosBatch.add(crearEventoReplanificacion(anterior, actual, motivo, horaEvento));
                eventosEmitidos++;
                System.out.println("[REPLANIFICACION] idPedido=" + actual.getIdPedido()
                        + " motivo=" + motivo
                        + " anterior=" + valorLog(anterior.getIdItinerario())
                        + " nuevo=" + valorLog(actual.getIdItinerario()));
            }

            state.getUltimaAsignacionPorEnvio().put(actual.getIdPedido(), actual);
        }

        if (cambiosDetectados > 0) {
            System.out.println("[REPLANIFICACION] eventosEmitidos=" + eventosEmitidos
                    + " cambiosDetectados=" + cambiosDetectados
                    + " bloque=" + ciclo
                    + " limite=" + MAX_EVENTOS_REPLANIFICACION_POR_BLOQUE);
        }
    }

    private void preservarAsignacionesVigentes(SolucionRuta solucion) {
        for (int i = 0; i < solucion.getAsignaciones().size(); i++) {
            RutaAsignada nueva = solucion.getAsignaciones().get(i);
            RutaAsignada anterior = state.getEnviosEnSeguimiento().get(nueva.getEnvio().getIdPedido());
            if (anterior == null || anterior.getItinerario() == null) {
                continue;
            }
            if (anterior.getItinerario().contieneVueloCancelado()) {
                continue;
            }
            if (!Objects.equals(anterior.getItinerario(), nueva.getItinerario())) {
                solucion.getAsignaciones().set(i, anterior);
            }
        }
    }

    private boolean esReplanificacionPorCancelacion(AsignacionResumen anterior, AsignacionResumen actual) {
        return "ASIGNADO".equals(anterior.getEstadoAsignacion())
                && cambioAsignacion(anterior, actual)
                && anterior.isContieneVueloCancelado();
    }

    private AsignacionResumen crearResumenAsignacion(RutaAsignada asignacion, Instant horaEvento, int ciclo) {
        Envio envio = asignacion.getEnvio();
        if (asignacion.getItinerario() == null || asignacion.getItinerario().getVuelos().isEmpty()) {
            return new AsignacionResumen(
                    envio.getIdPedido(),
                    "SIN_RUTA",
                    null,
                    List.of(),
                    null,
                    null,
                    envio.getOrigenIata(),
                    envio.getDestinoIata(),
                    horaEvento.toString(),
                    ciclo,
                    false
            );
        }

        List<VueloInstanciado> vuelos = asignacion.getItinerario().getVuelos();
        List<String> vuelosUsados = vuelos.stream().map(this::firmaVuelo).toList();
        return new AsignacionResumen(
                envio.getIdPedido(),
                "ASIGNADO",
                asignacion.getItinerario().getIdItinerario(),
                vuelosUsados,
                vuelosUsados.get(0),
                vuelosUsados.get(vuelosUsados.size() - 1),
                envio.getOrigenIata(),
                envio.getDestinoIata(),
                horaEvento.toString(),
                ciclo,
                vuelos.stream().anyMatch(VueloInstanciado::isEstaCancelado)
        );
    }

    private boolean cambioAsignacion(AsignacionResumen anterior, AsignacionResumen actual) {
        return !Objects.equals(anterior.getEstadoAsignacion(), actual.getEstadoAsignacion())
                || !Objects.equals(anterior.getIdItinerario(), actual.getIdItinerario())
                || !Objects.equals(anterior.getVuelosUsados(), actual.getVuelosUsados());
    }

    private EventoReplanificacionEnvioDTO crearEventoReplanificacion(
            AsignacionResumen anterior,
            AsignacionResumen actual,
            String motivo,
            Instant horaEvento
    ) {
        return new EventoReplanificacionEnvioDTO(
                horaEvento.toString(),
                actual.getIdPedido(),
                motivo,
                actual.getOrigenIata(),
                actual.getDestinoIata(),
                anterior.getIdItinerario(),
                actual.getIdItinerario(),
                anterior.getPrimerVuelo(),
                actual.getPrimerVuelo(),
                anterior.getEstadoAsignacion(),
                actual.getEstadoAsignacion(),
                horaEvento.toString(),
                "El envio cambio de ruta durante la planificacion del bloque"
        );
    }

    private String firmaVuelo(VueloInstanciado vuelo) {
        return vuelo.getCodigoBase() + "@" + (
                vuelo.getFechaHoraSalidaUtc() != null
                        ? vuelo.getFechaHoraSalidaUtc()
                        : vuelo.getFechaHoraSalida()
        );
    }

    private String valorLog(String valor) {
        return valor == null || valor.isBlank() ? "SIN_RUTA" : valor;
    }

    private Optional<IncumplimientoSla> encontrarPrimerIncumplimientoSla(Instant ventanaFinUtc) {
        Map<String, Aeropuerto> aeropuertos = state.getAeropuertosSnapshot();
        return state.getEnviosEnSeguimiento().values().stream()
                .filter(asignacion -> !state.getEnviosEntregados().contains(asignacion.getEnvio().getIdPedido()))
                .map(asignacion -> new IncumplimientoSla(
                        asignacion, PlanificadorUtils.calcularDeadlineSla(asignacion.getEnvio(), aeropuertos)
                ))
                .filter(incumplimiento -> !incumplimiento.deadline().isAfter(ventanaFinUtc))
                .filter(incumplimiento -> incumplimiento.asignacion().getItinerario() == null
                        || incumplimiento.asignacion().getItinerario().getFechaHoraLlegadaUtc()
                        .isAfter(incumplimiento.deadline()))
                .min(Comparator.comparing(IncumplimientoSla::deadline));
    }

    private void registrarColapsoSla(
            int ciclo,
            LocalDateTime ventanaInicio,
            LocalDateTime ventanaFin,
            SolucionRuta solucion,
            IncumplimientoSla incumplimiento,
            List<EventoBaseDTO> eventosBatch
    ) {
        Instant instante = incumplimiento.deadline();
        state.setEstado("COLAPSADA");
        state.registrarFinReal();
        state.setMotivoColapso("SLA_INCUMPLIDO");
        state.setDetalleColapso(crearDetalleColapsoSla(incumplimiento));
        state.setMetricasColapsoActuales(crearMetricasColapsoSla(
                ciclo, ventanaInicio, ventanaFin, solucion, incumplimiento
        ));

        eventosBatch.removeIf(evento -> Instant.parse(evento.getFechaHoraEvento()).isAfter(instante));
        eventosBatch.add(new EventoColapsoDTO(
                instante.toString(),
                simulacionId,
                instante.toString(),
                ciclo,
                "SLA_INCUMPLIDO",
                List.of("SLA_INCUMPLIDO", "ENVIO_NO_ENTREGADO_A_TIEMPO", "MALETA_NO_ENTREGADA_A_TIEMPO"),
                state.getMetricasColapsoActuales(),
                state.getDetalleColapso()
        ));
        System.out.println("[COLAPSO] causa=SLA_INCUMPLIDO idPedido="
                + incumplimiento.asignacion().getEnvio().getIdPedido() + " horaExacta=" + instante);
    }

    private MetricasColapsoDTO crearMetricasColapsoSla(
            int ciclo,
            LocalDateTime ventanaInicio,
            LocalDateTime ventanaFin,
            SolucionRuta solucion,
            IncumplimientoSla primerIncumplimiento
    ) {
        long pendientes = state.getEnviosEnSeguimiento().values().stream()
                .filter(a -> !state.getEnviosEntregados().contains(a.getEnvio().getIdPedido()))
                .count();
        int maletasPendientes = state.getEnviosEnSeguimiento().values().stream()
                .filter(a -> !state.getEnviosEntregados().contains(a.getEnvio().getIdPedido()))
                .mapToInt(a -> a.getEnvio().getCantidadMaletas())
                .sum();
        long slaIncumplidos = state.getEnviosEnSeguimiento().values().stream()
                .filter(a -> !state.getEnviosEntregados().contains(a.getEnvio().getIdPedido()))
                .filter(a -> !PlanificadorUtils.calcularDeadlineSla(a.getEnvio(), state.getAeropuertosSnapshot())
                        .isAfter(primerIncumplimiento.deadline()))
                .count();
        int maletasProcesadas = state.getEnviosEnSeguimiento().values().stream()
                .mapToInt(a -> a.getEnvio().getCantidadMaletas())
                .sum();

        MetricasColapsoDTO metricas = new MetricasColapsoDTO();
        metricas.setCiclo(ciclo);
        metricas.setVentanaInicio(ventanaInicio.toString());
        metricas.setVentanaFin(ventanaFin.toString());
        metricas.setFechaHoraColapsoExacta(primerIncumplimiento.deadline().toString());
        metricas.setEnviosProcesados(state.getEnviosRegistrados().size());
        metricas.setMaletasProcesadas(maletasProcesadas);
        metricas.setEnviosPendientes((int) pendientes);
        metricas.setMaletasPendientes(maletasPendientes);
        metricas.setSlaIncumplidos((int) slaIncumplidos);
        metricas.setEnviosSlaIncumplidos((int) slaIncumplidos);
        metricas.setPorcentajeSlaIncumplido(
                state.getEnviosRegistrados().isEmpty() ? 0.0 : slaIncumplidos / (double) state.getEnviosRegistrados().size()
        );
        metricas.setPrimerEnvioIncumplido(primerIncumplimiento.asignacion().getEnvio().getIdPedido());
        metricas.setDeadlinePrimerIncumplido(primerIncumplimiento.deadline().toString());
        metricas.setRetrasoMinutos(0);
        metricas.setFitnessUltimaSolucion(solucion.getFitness());
        metricas.setMotivoColapso("SLA_INCUMPLIDO");
        metricas.setCausaPrincipal("SLA_INCUMPLIDO");
        return metricas;
    }

    private DetalleColapsoDTO crearDetalleColapsoSla(IncumplimientoSla incumplimiento) {
        RutaAsignada asignacion = incumplimiento.asignacion();
        Envio envio = asignacion.getEnvio();
        DetalleColapsoDTO detalle = new DetalleColapsoDTO();
        detalle.setIdPedido(envio.getIdPedido());
        detalle.setOrigenIata(envio.getOrigenIata());
        detalle.setDestinoIata(envio.getDestinoIata());
        detalle.setCantidadMaletas(envio.getCantidadMaletas());
        detalle.setFechaHoraRegistro(PlanificadorUtils.obtenerFechaIngresoUtc(envio).toString());
        detalle.setDeadlineSla(incumplimiento.deadline().toString());
        detalle.setHoraColapso(incumplimiento.deadline().toString());
        detalle.setHoraSimulada(incumplimiento.deadline().toString());
        detalle.setTipoSla(PlanificadorUtils.obtenerTipoSla(envio, state.getAeropuertosSnapshot()));
        detalle.setMotivo("SLA_INCUMPLIDO");
        detalle.setEstadoEnvio(asignacion.getItinerario() == null ? "PENDIENTE_SIN_ITINERARIO" : "ASIGNADO_NO_ENTREGADO");
        detalle.setAeropuertoActual(state.getUltimoAeropuertoPorEnvio().get(envio.getIdPedido()));
        if (asignacion.getItinerario() != null) {
            detalle.setItinerarioAfectado(asignacion.getItinerario().getIdItinerario());
            if (!asignacion.getItinerario().getVuelos().isEmpty()) {
                detalle.setVueloAfectado(asignacion.getItinerario().getVuelos().get(0).getCodigoBase());
            }
        }
        return detalle;
    }

    private void extraerEventosVueloPostergados(
            Instant ventanaFinUtc,
            List<EventoBaseDTO> eventosBatch,
            Map<String, EventoVueloDTO> eventosPostergados,
            Set<String> clavesEventosPostergadosEnBatch
    ) {
        Iterator<Map.Entry<String, EventoVueloDTO>> iterator = eventosPostergados.entrySet().iterator();
        while (iterator.hasNext()) {
            EventoVueloDTO evento = iterator.next().getValue();
            if (!Instant.parse(evento.getFechaHoraEvento()).isAfter(ventanaFinUtc)) {
                iterator.remove();
                eventosBatch.add(evento);
                clavesEventosPostergadosEnBatch.add(claveEventoVuelo(evento));
            }
        }
    }

    private void agregarEventosVueloPostergados(
            List<EventoBaseDTO> eventosFuturos,
            Map<String, EventoVueloDTO> eventosPostergados
    ) {
        for (EventoBaseDTO evento : eventosFuturos) {
            if (!(evento instanceof EventoVueloDTO eventoVuelo)) {
                continue;
            }
            eventosPostergados.merge(
                    claveEventoVuelo(eventoVuelo),
                    eventoVuelo,
                    (existente, nuevo) -> {
                        simulacionEventosFactory.fusionarEventoVuelo(existente, nuevo);
                        return existente;
                    }
            );
        }
    }

    private void consolidarEventosVuelo(List<EventoBaseDTO> eventos) {
        Map<String, EventoVueloDTO> vuelosConsolidados = new java.util.LinkedHashMap<>();
        List<EventoBaseDTO> consolidados = new ArrayList<>();
        for (EventoBaseDTO evento : eventos) {
            if (!(evento instanceof EventoVueloDTO eventoVuelo)) {
                consolidados.add(evento);
                continue;
            }
            String clave = claveEventoVuelo(eventoVuelo);
            EventoVueloDTO existente = vuelosConsolidados.get(clave);
            if (existente == null) {
                vuelosConsolidados.put(clave, eventoVuelo);
                consolidados.add(eventoVuelo);
            } else {
                simulacionEventosFactory.fusionarEventoVuelo(existente, eventoVuelo);
            }
        }
        eventos.clear();
        eventos.addAll(consolidados);
    }

    private String claveEventoVuelo(EventoVueloDTO evento) {
        return evento.getTipo() + "|" + evento.getCodigoVuelo() + "|" + evento.getHoraSalidaUtc();
    }

    private void aplicarFisicaHasta(
            List<EventoBaseDTO> eventos,
            Instant instanteColapso,
            Set<String> clavesEventosPostergadosEnBatch
    ) {
        aplicarFisicaHasta(eventos, instanteColapso, clavesEventosPostergadosEnBatch, List.of());
    }

    private void registrarColapsoCapacidad(
            int ciclo,
            LocalDateTime ventanaInicio,
            LocalDateTime ventanaFin,
            SolucionRuta solucion,
            ColapsoCapacidad colapso,
            List<EventoBaseDTO> eventosBatch
    ) {
        if (!colapsoPublicado.compareAndSet(false, true)) return;
        String estadoAnterior = state.getEstado();
        state.setEstado("COLAPSADA");
        state.registrarFinReal();
        state.setMotivoColapso("CAPACIDAD_AEROPUERTO_SUPERADA");

        double porcentaje = colapso.ocupacion() * 100.0 / colapso.capacidad();
        DetalleColapsoDTO detalle = new DetalleColapsoDTO();
        detalle.setIdPedido(colapso.envioId());
        detalle.setCantidadMaletas(colapso.cantidadMovimiento());
        detalle.setMotivo("CAPACIDAD_AEROPUERTO_SUPERADA");
        detalle.setVueloAfectado(colapso.vueloId());
        detalle.setHoraSimulada(colapso.instante().toString());
        detalle.setTipo("CAPACIDAD_AEROPUERTO");
        detalle.setCodigoAeropuerto(colapso.aeropuerto());
        detalle.setCapacidad(colapso.capacidad());
        detalle.setMaletasActuales(colapso.ocupacion());
        detalle.setPorcentajeOcupacion(porcentaje);
        detalle.setHoraColapso(colapso.instante().toString());
        state.setDetalleColapso(detalle);

        MetricasColapsoDTO metricas = new MetricasColapsoDTO();
        metricas.setCiclo(ciclo);
        metricas.setVentanaInicio(ventanaInicio.toString());
        metricas.setVentanaFin(ventanaFin.toString());
        metricas.setEnviosProcesados(state.getEnviosRegistrados().size());
        metricas.setMaletasProcesadas(state.getEnviosEnSeguimiento().values().stream()
                .mapToInt(a -> a.getEnvio().getCantidadMaletas()).sum());
        metricas.setAeropuertosSaturados(1);
        metricas.setOcupacionAeropuertoMaxima(porcentaje / 100.0);
        metricas.setFitnessUltimaSolucion(solucion.getFitness());
        metricas.setMotivoColapso("CAPACIDAD_AEROPUERTO_SUPERADA");
        metricas.setCodigoAeropuertoColapsado(colapso.aeropuerto());
        metricas.setMaletasActualesAeropuerto(colapso.ocupacion());
        metricas.setCapacidadAeropuerto(colapso.capacidad());
        metricas.setPorcentajeOcupacionAeropuerto(porcentaje);
        metricas.setCausaPrincipal("CAPACIDAD_AEROPUERTO_SUPERADA");
        metricas.setFechaHoraColapsoExacta(colapso.instante().toString());
        state.setMetricasColapsoActuales(metricas);

        eventosBatch.removeIf(evento -> Instant.parse(evento.getFechaHoraEvento()).isAfter(colapso.instante()));
        EventoColapsoDTO evento = new EventoColapsoDTO(
                colapso.instante().toString(), simulacionId, colapso.instante().toString(), ciclo,
                "CAPACIDAD_AEROPUERTO_SUPERADA", List.of("CAPACIDAD_AEROPUERTO_SUPERADA"), metricas, detalle
        );
        evento.setCodigoAeropuerto(colapso.aeropuerto());
        evento.setNombreAeropuerto(colapso.nombreAeropuerto());
        evento.setCapacidadMaxima(colapso.capacidad());
        evento.setOcupacionActual(colapso.ocupacion());
        evento.setExceso(colapso.ocupacion() - colapso.capacidad());
        evento.setPorcentajeOcupacion(porcentaje);
        evento.setMotivo("CAPACIDAD_AEROPUERTO_SUPERADA");
        eventosBatch.add(evento);

        System.out.println("[SIM5D-COLLAPSE] simulacionId=" + simulacionId
                + " bloque=" + ciclo
                + " aeropuerto=" + colapso.aeropuerto()
                + " capacidadMaxima=" + colapso.capacidad()
                + " ocupacionDetectada=" + colapso.ocupacion()
                + " exceso=" + (colapso.ocupacion() - colapso.capacidad())
                + " instanteSimulado=" + colapso.instante()
                + " envioCausante=" + colapso.envioId()
                + " vueloCausante=" + colapso.vueloId()
                + " estadoAnterior=" + estadoAnterior
                + " estadoNuevo=COLAPSADA");
    }

    private Optional<ColapsoCapacidad> aplicarFisicaHasta(
            List<EventoBaseDTO> eventos,
            Instant instanteColapso,
            Set<String> clavesEventosPostergadosEnBatch,
            List<RutaAsignada> checkIns
    ) {
        List<EventoVueloDTO> eventosVuelo = eventos.stream()
                .filter(EventoVueloDTO.class::isInstance)
                .map(EventoVueloDTO.class::cast)
                .filter(evento -> evento.getTipo() != TipoEvento.VUELO_CANCELADO)
                .filter(evento -> instanteColapso == null
                        || !Instant.parse(evento.getFechaHoraEvento()).isAfter(instanteColapso))
                .toList();

        List<MovimientoFisico> movimientos = new ArrayList<>();
        for (EventoVueloDTO evento : eventosVuelo) {
            int prioridad = evento.getTipo() == TipoEvento.VUELO_DESPEGA ? 0 : 2;
            movimientos.add(new MovimientoFisico(
                    Instant.parse(evento.getFechaHoraEvento()), prioridad, evento, null
            ));
        }
        for (RutaAsignada checkIn : checkIns) {
            Instant instanteOriginal = PlanificadorUtils.obtenerFechaIngresoUtc(checkIn.getEnvio());
            LocalDateTime tiempoActual = state.getTiempoActual() != null ? state.getTiempoActual() : horaInicio;
            Instant inicioBloque = tiempoActual.toInstant(ZoneOffset.UTC);
            Instant instante = instanteOriginal.isBefore(inicioBloque) ? inicioBloque : instanteOriginal;
            if (instanteColapso == null || !instante.isAfter(instanteColapso)) {
                movimientos.add(new MovimientoFisico(instante, 1, null, checkIn));
            }
        }

        // Regla operativa para timestamps iguales: primero SALIDA, luego CHECK-IN y finalmente ATERRIZAJE.
        // Así se libera capacidad antes de registrar entradas simultáneas y se evita un falso colapso.
        movimientos.sort(Comparator.comparing(MovimientoFisico::instante)
                .thenComparingInt(MovimientoFisico::prioridad)
                .thenComparing(movimiento -> movimiento.idDeterministico()));

        for (MovimientoFisico movimiento : movimientos) {
            Optional<ColapsoCapacidad> colapso;
            if (movimiento.checkIn() != null) {
                colapso = aplicarCheckIn(movimiento.checkIn(), movimiento.instante(), eventos);
            } else {
                EventoVueloDTO evento = movimiento.eventoVuelo();
                colapso = aplicarFisicaVuelo(
                        evento,
                        movimiento.instante(),
                        eventos,
                        clavesEventosPostergadosEnBatch.contains(claveEventoVuelo(evento))
                );
                marcarEnviosEntregadosHasta(movimiento.instante(), eventos);
            }
            if (colapso.isPresent()) {
                return colapso;
            }
        }
        return Optional.empty();
    }

    private Optional<ColapsoCapacidad> aplicarCheckIn(
            RutaAsignada asignacion,
            Instant horaEvento,
            List<EventoBaseDTO> eventos
    ) {
        Envio envio = asignacion.getEnvio();
        return sumarInventarioYDetectarColapso(
                envio.getOrigenIata(), envio.getCantidadMaletas(), horaEvento,
                "ENTRADA", envio.getIdPedido(), null, eventos
        );
    }

    private Optional<ColapsoCapacidad> aplicarFisicaVuelo(
            EventoVueloDTO evento,
            Instant horaEvento,
            List<EventoBaseDTO> eventos,
            boolean esPostergado
    ) {
        if (evento.getTipo() == TipoEvento.VUELO_DESPEGA) {
            String origen = evento.getOrigenIata();
            int inventarioDisponible = state.getInventarioSnapshot().getOrDefault(origen, 0);
            if (evento.getCantidadMaletas() > inventarioDisponible) {
                ajustarCargaEvento(evento, inventarioDisponible);
            }
            int maletasCargadas = registrarCargaRealDespachada(evento, inventarioDisponible);
            if (maletasCargadas < evento.getCantidadMaletas()) {
                ajustarCargaEvento(evento, maletasCargadas);
            }
            simulacionStateMutator.descontarMaletasSalidaVuelo(
                    origen,
                    evento.getCantidadMaletas(),
                    evento.getCodigoVuelo(),
                    evento.getFechaHoraEvento(),
                    esPostergado
            );
            agregarEventoInventario(origen, horaEvento, eventos);
            return Optional.empty();
        } else if (evento.getTipo() == TipoEvento.VUELO_ATERRIZA) {
            aplicarCargaRealDespachada(evento);
            String destino = evento.getDestinoIata();
            int entregadasEnDestino = registrarEntregasDirectas(evento, horaEvento);
            int maletasParaAlmacenar = evento.getCantidadMaletas() - entregadasEnDestino;
            if (maletasParaAlmacenar <= 0) {
                agregarEventoInventario(destino, horaEvento, eventos);
                return Optional.empty();
            }
            return sumarInventarioYDetectarColapso(
                    destino, maletasParaAlmacenar, horaEvento, "ESCALA",
                    primerEnvioEvento(evento), evento.getCodigoVuelo(), eventos
            );
        }
        return Optional.empty();
    }

    private Optional<ColapsoCapacidad> sumarInventarioYDetectarColapso(
            String codigoIata,
            int cantidad,
            Instant instante,
            String tipoMovimiento,
            String envioId,
            Long vueloId,
            List<EventoBaseDTO> eventos
    ) {
        Aeropuerto aeropuerto = state.getAeropuertosSnapshot().get(codigoIata);
        int antes = state.getInventarioSnapshot().getOrDefault(codigoIata, 0);
        if (aeropuerto == null || cantidad <= 0) {
            return Optional.empty();
        }
        int despuesCompleto = antes + cantidad;
        boolean supera = despuesCompleto > aeropuerto.getCapacidadAlmacen();
        int despuesAplicado = supera ? aeropuerto.getCapacidadAlmacen() + 1 : despuesCompleto;
        state.getInventarioSnapshot().put(codigoIata, despuesAplicado);
        agregarEventoInventario(codigoIata, instante, eventos);

        System.out.println("[SIM5D-CAPACITY-TRACE] bloque=" + (state.getBloquesProcesados() + 1)
                + " aeropuerto=" + codigoIata
                + " instante=" + instante
                + " capacidadMaxima=" + aeropuerto.getCapacidadAlmacen()
                + " ocupacionAntes=" + antes
                + " movimiento=" + tipoMovimiento
                + " cantidadMaletasMovimiento=" + cantidad
                + " ocupacionDespues=" + despuesAplicado
                + " envioId=" + envioId
                + " vueloId=" + vueloId
                + " superaCapacidad=" + supera);
        System.out.println("[SIM5D-CAPACITY-COMPARE] aeropuerto=" + codigoIata
                + " instante=" + instante
                + " ocupacionSegunTabu=" + despuesCompleto
                + " ocupacionSegunSimulador=" + despuesAplicado
                + " ocupacionSegunFrontendDTO=" + despuesAplicado
                + " coincide=" + (despuesCompleto == despuesAplicado));

        if (!supera) return Optional.empty();
        return Optional.of(new ColapsoCapacidad(
                codigoIata, aeropuerto.getCiudad(), aeropuerto.getCapacidadAlmacen(), despuesAplicado,
                instante, tipoMovimiento, cantidad, envioId, vueloId
        ));
    }

    private String primerEnvioEvento(EventoVueloDTO evento) {
        return evento.getCodigoEnvios() == null || evento.getCodigoEnvios().isEmpty()
                ? null : evento.getCodigoEnvios().get(0);
    }

    private int registrarCargaRealDespachada(EventoVueloDTO evento, int inventarioDisponible) {
        int cargaReal = Math.min(evento.getCantidadMaletas(), Math.max(inventarioDisponible, 0));
        Set<String> enviosCargados = seleccionarEnviosCargados(evento, cargaReal);
        int maletasCargadas = enviosCargados.stream()
                .map(state.getEnviosEnSeguimiento()::get)
                .filter(java.util.Objects::nonNull)
                .mapToInt(asignacion -> asignacion.getEnvio().getCantidadMaletas())
                .sum();
        String clave = claveInstanciaVuelo(evento);
        maletasDespachadasPorVuelo.put(clave, maletasCargadas);
        enviosDespachadosPorVuelo.put(clave, enviosCargados);
        return maletasCargadas;
    }

    private Set<String> seleccionarEnviosCargados(EventoVueloDTO evento, int cargaMaxima) {
        Set<String> enviosCargados = new LinkedHashSet<>();
        int restante = Math.max(cargaMaxima, 0);
        List<RutaAsignada> asignaciones = state.getEnviosEnSeguimiento().values().stream()
                .filter(asignacion -> asignacion.getItinerario() != null)
                .filter(asignacion -> !state.getEnviosEntregados().contains(asignacion.getEnvio().getIdPedido()))
                .filter(asignacion -> contieneVuelo(asignacion, evento))
                .sorted(Comparator.comparing(asignacion -> asignacion.getEnvio().getIdPedido()))
                .toList();
        for (RutaAsignada asignacion : asignaciones) {
            int cantidad = asignacion.getEnvio().getCantidadMaletas();
            if (cantidad > restante) {
                continue;
            }
            enviosCargados.add(asignacion.getEnvio().getIdPedido());
            restante -= cantidad;
        }
        return enviosCargados;
    }

    private boolean contieneVuelo(RutaAsignada asignacion, EventoVueloDTO evento) {
        return asignacion.getItinerario().getVuelos().stream()
                .anyMatch(vuelo -> vuelo.getCodigoBase().equals(evento.getCodigoVuelo())
                        && vuelo.getFechaHoraSalidaUtc().toString().equals(evento.getHoraSalidaUtc()));
    }

    private void aplicarCargaRealDespachada(EventoVueloDTO evento) {
        Integer cargaReal = maletasDespachadasPorVuelo.get(claveInstanciaVuelo(evento));
        if (cargaReal != null && cargaReal < evento.getCantidadMaletas()) {
            ajustarCargaEvento(evento, cargaReal);
        }
    }

    private void ajustarCargaEvento(EventoVueloDTO evento, int cantidadMaletas) {
        int cantidadAjustada = Math.max(cantidadMaletas, 0);
        evento.setCantidadMaletas(cantidadAjustada);
        int capacidad = evento.getCapacidadMax();
        double porcentaje = capacidad > 0 ? (cantidadAjustada * 100.0) / capacidad : 0.0;
        evento.setPorcentajeOcupacion(porcentaje);
        if (porcentaje >= 85.0) {
            evento.setEstado(EstadoCapacidad.ROJO);
        } else if (porcentaje >= 60.0) {
            evento.setEstado(EstadoCapacidad.AMARILLO);
        } else {
            evento.setEstado(EstadoCapacidad.VERDE);
        }
    }

    private int registrarEntregasDirectas(EventoVueloDTO evento, Instant horaEvento) {
        int entregadas = 0;
        Set<String> enviosDespachados = enviosDespachadosPorVuelo.get(claveInstanciaVuelo(evento));
        for (RutaAsignada asignacion : state.getEnviosEnSeguimiento().values()) {
            String idPedido = asignacion.getEnvio().getIdPedido();
            if (asignacion.getItinerario() == null || state.getEnviosEntregados().contains(idPedido)) {
                continue;
            }
            if (enviosDespachados != null && !enviosDespachados.contains(idPedido)) {
                continue;
            }
            var ultimoVuelo = asignacion.getItinerario().getVuelos()
                    .get(asignacion.getItinerario().getVuelos().size() - 1);
            if (ultimoVuelo.getCodigoBase().equals(evento.getCodigoVuelo())
                    && ultimoVuelo.getFechaHoraLlegadaUtc().equals(horaEvento)) {
                state.getEnviosEntregados().add(idPedido);
                state.getUltimoAeropuertoPorEnvio().put(idPedido, asignacion.getEnvio().getDestinoIata());
                entregadas += asignacion.getEnvio().getCantidadMaletas();
            }
        }
        return entregadas;
    }

    private String claveInstanciaVuelo(EventoVueloDTO evento) {
        return claveInstanciaVuelo(evento.getCodigoVuelo(), evento.getHoraSalidaUtc());
    }

    private void agregarEventoInventario(String codigoIata, Instant horaEvento, List<EventoBaseDTO> eventos) {
        Aeropuerto aeropuerto = state.getAeropuertosSnapshot().get(codigoIata);
        int inventario = state.getInventarioSnapshot().getOrDefault(codigoIata, 0);
        eventos.add(simulacionEventosFactory.crearEventoAeropuerto(aeropuerto, inventario, horaEvento, this.state));
    }

    private void marcarEnviosEntregadosHasta(Instant limite, List<EventoBaseDTO> eventos) {
        for (RutaAsignada asignacion : state.getEnviosEnSeguimiento().values()) {
            if (asignacion.getItinerario() == null) continue;
            if (!asignacion.getItinerario().getFechaHoraLlegadaUtc().isAfter(limite)) {
                String idPedido = asignacion.getEnvio().getIdPedido();
                if (!envioFueDespachadoEnUltimoVuelo(asignacion, idPedido)) {
                    continue;
                }
                if (!state.getEnviosEntregados().add(idPedido)) {
                    continue;
                }
                String destino = asignacion.getEnvio().getDestinoIata();
                simulacionStateMutator.restarMaletas(destino, asignacion.getEnvio().getCantidadMaletas());
                agregarEventoInventario(destino, asignacion.getItinerario().getFechaHoraLlegadaUtc(), eventos);
                state.getUltimoAeropuertoPorEnvio().put(idPedido, asignacion.getEnvio().getDestinoIata());
            }
        }
    }

    private boolean envioFueDespachadoEnUltimoVuelo(RutaAsignada asignacion, String idPedido) {
        var vuelos = asignacion.getItinerario().getVuelos();
        if (vuelos.isEmpty()) {
            return true;
        }
        var ultimoVuelo = vuelos.get(vuelos.size() - 1);
        Set<String> enviosDespachados = enviosDespachadosPorVuelo.get(claveInstanciaVuelo(
                ultimoVuelo.getCodigoBase(), ultimoVuelo.getFechaHoraSalidaUtc().toString()
        ));
        return enviosDespachados == null || enviosDespachados.contains(idPedido);
    }

    private String claveInstanciaVuelo(Long codigoVuelo, String horaSalidaUtc) {
        return codigoVuelo + "|" + horaSalidaUtc;
    }

    private void agregarAlertasAeropuertosSaturados(List<EventoBaseDTO> eventos) {
        double umbral = configuracionColapsoDTO != null ? configuracionColapsoDTO.getUmbralAeropuerto() : 1.0;
        List<EventoBaseDTO> alertas = eventos.stream()
                .filter(EventoAeropuertoDTO.class::isInstance)
                .map(EventoAeropuertoDTO.class::cast)
                .filter(evento -> evento.getCapacidadAlmacen() > 0)
                .filter(evento -> evento.getMaletasActuales() / (double) evento.getCapacidadAlmacen() >= umbral)
                .map(simulacionEventosFactory::crearAlertaAeropuertoSaturado)
                .map(EventoBaseDTO.class::cast)
                .toList();
        eventos.addAll(alertas);
    }

    private void agregarEventosVuelosCancelados(
            LocalDateTime ventanaInicio,
            LocalDateTime ventanaFin,
            List<EventoBaseDTO> eventos
    ) {
        List<VueloInstanciado> vuelosCancelados = contextoDatos == null
                ? planificadorService.obtenerVuelosCanceladosEnVentana(ventanaInicio, ventanaFin)
                : planificadorService.obtenerVuelosCanceladosEnVentana(
                        ventanaInicio,
                        ventanaFin,
                        contextoDatos.vuelos(),
                        contextoDatos.aeropuertos(),
                        contextoDatos.incidencias()
                );
        for (var vuelo : vuelosCancelados) {
            eventos.add(simulacionEventosFactory.crearEventoVueloCancelado(vuelo));
        }
    }

    private SolucionRuta calcularSolucion(
            LocalDateTime ventanaInicio,
            LocalDateTime ventanaFin,
            Map<String, Integer> inventarioReservado,
            List<Envio> enviosOperacionDia
    ) {
        if (contextoDatos == null) {
            return esOperacionDia()
                    ? planificadorService.calcularSolucionOperacionDia(
                            algoritmo, ventanaInicio, ventanaFin, state.getEnviosPendientes(), inventarioReservado,
                            enviosOperacionDia
                    )
                    : planificadorService.calcularSolucion(
                            algoritmo, ventanaInicio, ventanaFin, state.getEnviosPendientes(), inventarioReservado
                    );
        }
        return esOperacionDia()
                ? planificadorService.calcularSolucionOperacionDia(
                        algoritmo, ventanaInicio, ventanaFin, state.getEnviosPendientes(), inventarioReservado,
                        enviosOperacionDia, contextoDatos.vuelos(), contextoDatos.aeropuertos(),
                        contextoDatos.incidencias()
                )
                : planificadorService.calcularSolucion(
                        algoritmo, ventanaInicio, ventanaFin, state.getEnviosPendientes(), inventarioReservado,
                        contextoDatos.vuelos(), contextoDatos.aeropuertos(), contextoDatos.incidencias()
                );
    }

    private void actualizarPendientesParaSiguienteCiclo(SolucionRuta solucion) {
        state.setEnviosPendientes(solucion.obtenerEnviosConConflictos());
        if (!state.getEnviosPendientes().isEmpty()) {
            System.out.println("[SIMULADOR] enviosPendientes=" + state.getEnviosPendientes().size());
        }
    }

    private boolean debeSaltarPlanificacionOperacionDia(
            List<Envio> enviosNuevos,
            List<EventoBaseDTO> eventosBatch,
            Map<String, EventoVueloDTO> eventosVueloPostergados
    ) {
        return esOperacionDia()
                && enviosNuevos.isEmpty()
                && state.getEnviosPendientes().isEmpty()
                && !hayEnviosActivosEnSeguimiento()
                && eventosBatch.isEmpty()
                && eventosVueloPostergados.isEmpty();
    }

    private boolean hayEnviosActivosEnSeguimiento() {
        return state.getEnviosEnSeguimiento().keySet().stream()
                .anyMatch(idPedido -> !state.getEnviosEntregados().contains(idPedido));
    }

    private void procesarBloqueOperacionSinEnvios(
            LocalDateTime ventanaInicio,
            LocalDateTime ventanaFin,
            long inicioCronometroTa
    ) {
        state.setSolucionActual(new SolucionRuta());
        state.setEnviosPendientes(List.of());
        long taCalculadoMs = System.currentTimeMillis() - inicioCronometroTa;
        this.tiempoUltimoLoteMs = taCalculadoMs;

        System.out.println("[OPERACION-DIA] enviosNuevos=0");
        System.out.println("[OPERACION-DIA] sinEnvios=true skipPlanificador=true");
        System.out.println("[OPERACION-DIA] estado=EN_EJECUCION");

        state.setTiempoActual(ventanaFin);
        EventoBaseDTO eventoActivo = new EventoEstadoSimulacionDTO(
                TipoEvento.OPERACION_DIA_ACTIVA,
                ventanaInicio.toInstant(ZoneOffset.UTC).toString(),
                simulacionId,
                horaInicio.toString(),
                "EN_EJECUCION",
                "Operacion activa. Registra envios para iniciar la planificacion."
        );
        publicarLote(
                List.of(eventoActivo),
                List.of(),
                ventanaInicio.toInstant(ZoneOffset.UTC),
                ventanaFin.toInstant(ZoneOffset.UTC)
        );
        state.guardarSnapshot();
        state.setBloquesProcesados(state.getBloquesProcesados() + 1);

        System.out.printf("[LOTE-ENVIADO] numero=%d | eventos=1 | ventana=%s -> %s | taTotal=%dms | sa=%dms | operacionSinEnvios=true%n",
                state.getUltimoLoteEmitidoNumero().get(), ventanaInicio, ventanaFin, taCalculadoMs, saMs);

        if (state.getTiempoActual().isBefore(horaFin)) {
            esperarConControl();
        }
    }

    private void publicarMetricasCapacidad(SolucionRuta solucion) {
        int enviosSlaIncumplidos = state.getMetricasColapsoActuales() != null
                ? state.getMetricasColapsoActuales().getEnviosSlaIncumplidos()
                : solucion.getExcedeSlaCount();
        System.out.println("[SIMULADOR-CAPACIDAD-METRICA] maxOcupacionAeropuerto="
                + simulacionStateMutator.obtenerMaxOcupacionAeropuerto()
                + " aeropuertosSobreCapacidad=" + simulacionStateMutator.contarAeropuertosSobreCapacidad()
                + " enviosPendientesPorCapacidad=" + solucion.getSinItinerarioCount()
                + " enviosSlaIncumplidos=" + enviosSlaIncumplidos);
    }

    private boolean esOperacionDia() {
        return MODO_OPERACION_DIA.equals(modo);
    }

    private Comparator<EventoBaseDTO> comparadorEventos() {
        return Comparator.comparing(evento -> Instant.parse(evento.getFechaHoraEvento()));
    }

    private void publicarControl(TipoEvento tipoEvento) {
        Instant ventana = state.getTiempoActual() != null
                ? state.getTiempoActual().toInstant(ZoneOffset.UTC)
                : Instant.now();
        EventoBaseDTO evento = tipoEvento == TipoEvento.SIMULACION_INICIADA
                ? new EventoEstadoSimulacionDTO(
                        tipoEvento,
                        ventana.toString(),
                        simulacionId,
                        horaInicio.toString(),
                        "EN_EJECUCION",
                        "Simulacion iniciada, preparando primer bloque"
                )
                : new EventoBaseDTO(tipoEvento, ventana.toString());
        publicarLote(List.of(evento), List.of(),ventana, ventana);
    }

    private void publicarConfiguracionRendimiento() {
        if (esOperacionDia()) {
            long minutosSimulados = java.time.Duration.between(horaInicio, horaFin).toMinutes();
            long bloques = (long) Math.ceil(minutosSimulados / (double) k);
            System.out.println("[OPERACION-DIA] saltoAlgoritmoMinutos=" + k
                    + " saltoConsumoDatosMinutos=" + k
                    + " esperaEntreBloquesMs=" + saMs
                    + " bloquesEstimados=" + bloques);
            return;
        }
        if (horaFin == null) {
            System.out.println("[SIMULACION-CONFIG] modo=COLAPSO saltoAlgoritmoMinutos=" + k
                    + " saltoConsumoDatosMinutos=" + k + " esperaEntreBloquesMs=" + saMs);
            return;
        }
        long minutosSimulados = java.time.Duration.between(horaInicio, horaFin).toMinutes();
        long bloques = (long) Math.ceil(minutosSimulados / (double) k);
        double duracionEstimadaMinutos = Math.max(bloques - 1, 0) * saMs / 60_000.0;
        System.out.println("[SIM5D-CONFIG] inicio=" + horaInicio
                + " fin=" + horaFin
                + " minutosSimulados=" + minutosSimulados
                + " bloquesEstimados=" + bloques
                + " saltoAlgoritmoMinutos=" + k
                + " saltoConsumoDatosMinutos=" + k
                + " esperaEntreBloquesMs=" + saMs
                + " duracionEstimadaMinutos=" + duracionEstimadaMinutos);
    }

    private void publicarLote(List<EventoBaseDTO> eventos, List<EnvioDTO> envios, Instant ventanaInicio, Instant ventanaFin) {
        if (eventos.isEmpty()) return;
        LoteEventosDTO lote = new LoteEventosDTO(
                simulacionId,
                state.siguienteLote(),
                ventanaInicio != null ? ventanaInicio.toString() : null,
                ventanaFin != null ? ventanaFin.toString() : null,
                eventos.size(),
                eventos,
                envios
        );
        state.setUltimoLoteEmitido(lote);
        webSocketPublisher.publicarLote(simulacionId, lote);
    }

    private void esperarConControl() {
        long tiempoRestante = Math.max(0, saMs - tiempoUltimoLoteMs);
        long acumulado = 0L;
        long paso = 200L;
        while (acumulado < tiempoRestante) {
            esperarSiPausadaODetenida();
            long dormirMs = Math.min(paso, tiempoRestante - acumulado);
            dormir(dormirMs);
            acumulado += dormirMs;
        }
    }

    private void esperarHasta(long instanteObjetivoMs) {
        while (System.currentTimeMillis() < instanteObjetivoMs) {
            verificarDetencion();
            esperarSiPausadaODetenida();
            long restante = instanteObjetivoMs - System.currentTimeMillis();
            if (restante <= 0) return;
            try {
                Thread.sleep(Math.min(restante, 250L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                verificarDetencion();
                throw new SimulacionDetenidaException();
            }
        }
    }

    private boolean esSimulacionCincoDias() {
        return "1".equals(modo) && horaFin != null;
    }

    private long memoriaUsada() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private long gcCount() {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(bean.getCollectionCount(), 0L)).sum();
    }

    private long gcTimeMs() {
        return java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(bean.getCollectionTime(), 0L)).sum();
    }

    private void esperarSiPausadaODetenida() {
        boolean avisoPausaEmitido = false;
        while (pausada.get()) {
            verificarDetencion();
            if (!avisoPausaEmitido) {
                publicarControl(TipoEvento.SIMULACION_EN_PAUSA);
                avisoPausaEmitido = true;
            }
            dormir(500);
        }
        verificarDetencion();
    }

    private void verificarDetencion() {
        if (detenida.get()) throw new SimulacionDetenidaException();
    }

    private boolean esTerminal() {
        return "FINALIZADA".equals(state.getEstado())
                || "DETENIDA".equals(state.getEstado())
                || "ERROR".equals(state.getEstado())
                || "COLAPSADA".equals(state.getEstado());
    }

    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (detenida.get()) throw new SimulacionDetenidaException();
        }
    }

    public void pausar() {
        if (esTerminal()) return;
        pausada.set(true);
        state.setEstado("PAUSADA");
        publicarControl(TipoEvento.SIMULACION_PAUSADA);
    }

    public void reanudar() {
        if (esTerminal()) return;
        pausada.set(false);
        state.setEstado("EN_PROCESO");
        publicarControl(TipoEvento.SIMULACION_REANUDADA);
    }

    public void detener() {
        detenida.set(true);
        pausada.set(false);
        state.setEstado("DETENIDA");
        state.registrarFinReal();
        if (hilo != null) hilo.interrupt();
    }

    public boolean estaPausada() {
        return pausada.get();
    }

    public boolean estaDetenida() {
        return detenida.get();
    }

    public LocalDateTime getFechaInicio() {
        return horaInicio;
    }

    private record IncumplimientoSla(RutaAsignada asignacion, Instant deadline) {
    }

    private record MovimientoFisico(
            Instant instante,
            int prioridad,
            EventoVueloDTO eventoVuelo,
            RutaAsignada checkIn
    ) {
        private String idDeterministico() {
            if (checkIn != null) return checkIn.getEnvio().getIdPedido();
            return eventoVuelo.getCodigoVuelo() + "|" + eventoVuelo.getTipo();
        }
    }

    private record ColapsoCapacidad(
            String aeropuerto,
            String nombreAeropuerto,
            int capacidad,
            int ocupacion,
            Instant instante,
            String tipoMovimiento,
            int cantidadMovimiento,
            String envioId,
            Long vueloId
    ) {
    }

    private static class SimulacionDetenidaException extends RuntimeException {
    }
}
