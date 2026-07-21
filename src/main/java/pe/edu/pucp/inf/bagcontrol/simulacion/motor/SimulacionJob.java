package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Getter;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.auth.UsuarioSesion;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.metricas.MetricasPlanificacionBloque;
import pe.edu.pucp.inf.bagcontrol.planificacion.metricas.PlanificacionInstrumentacion;
import pe.edu.pucp.inf.bagcontrol.planificacion.deadline.DeadlinePlanificacion;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.CancelacionVueloResponseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.DetalleColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.VueloCancelableDTO;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class SimulacionJob implements Runnable {

    @Getter
    private volatile int saMs;

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
    private final Map<String, Integer> maletasDespachadasPorVuelo = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Set<String>> enviosDespachadosPorVuelo = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> aterrizajesProcesados = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> vuelosDespachadosActualizados = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> vuelosCanceladosManualmente = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> enviosForzadosAReplanificar = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> enviosConCheckIn = new HashSet<>();
    private long tiempoUltimoLoteMs = 0L;
    private final CalendarioPublicaciones calendarioPublicaciones = new CalendarioPublicaciones();
    private final AtomicBoolean planificacionActiva = new AtomicBoolean(false);
    private final AtomicReference<BloquePreparado> bloquePreparado = new AtomicReference<>();
    private final AtomicLong versionPlan = new AtomicLong(1L);
    private final AtomicBoolean cancelacionCooperativaSolicitada = new AtomicBoolean(false);
    private int bloquesConsecutivosCercaLimite = 0;
    public static final int SA_INICIAL_MS = 35_000;
    public static final int SA_MAXIMO_MS = 40_000;
    public static final int MARGEN_SEGURIDAD_MS = 4_000;
    private static final int MAX_EVENTOS_REPLANIFICACION_POR_BLOQUE = 50;
    private static final String MODO_OPERACION_DIA = "0";
    @Getter
    private final String modo;
    @Getter
    private final UsuarioSesion propietario;
    private volatile SimulacionContextoDatos contextoDatos;

    private volatile Thread hilo;

    public SimulacionJob(
            String simulacionId, LocalDateTime horaInicio, LocalDateTime horaFin, int k, String algoritmo,
            PlanificadorService planificadorService, AeropuertoRepository aeropuertoRepository,
            WebSocketPublisher webSocketPublisher, SimulacionEventosFactory simulacionEventosFactory,
            SimulacionState state, ConfiguracionColapsoDTO configuracionColapsoDTO,
            SimulacionStateMutator simulacionStateMutator, String modo
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
                null,
                null
        );
    }

    public SimulacionJob(
            String simulacionId, LocalDateTime horaInicio, LocalDateTime horaFin, int k, String algoritmo,
            PlanificadorService planificadorService, AeropuertoRepository aeropuertoRepository,
            WebSocketPublisher webSocketPublisher, SimulacionEventosFactory simulacionEventosFactory,
            SimulacionState state, ConfiguracionColapsoDTO configuracionColapsoDTO,
            SimulacionStateMutator simulacionStateMutator, String modo,
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
        this.saMs = (modo != null && "0".equals(modo)) ? k * 60 * 1000 : SA_INICIAL_MS;
        this.configuracionColapsoDTO = configuracionColapsoDTO;
        this.simulacionStateMutator = simulacionStateMutator;
        this.modo = modo;
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
        } else {
            planificadorService.precargarEnvios(horaInicio);
        }
        publicarControl(TipoEvento.SIMULACION_INICIADA);

        LocalDateTime tiempoFin = horaFin == null ? LocalDateTime.MAX : horaFin;
        Map<String, EventoVueloDTO> eventosVueloPostergados = new java.util.LinkedHashMap<>();
        publicarConfiguracionRendimiento();

        while (state.getTiempoActual().isBefore(tiempoFin)) {
            // --- FASE 1: INICIO DE MEDICIÓN DE TA ---
            Instant inicioRealCalculo = Instant.now();
            long inicioCronometroTa = inicioRealCalculo.toEpochMilli();
            int frecuenciaBloqueMs = saMs;
            long fronteraProgramadaMs = calendarioPublicaciones.tieneOrigen()
                    ? calendarioPublicaciones.getFronteraProgramadaMs()
                    : inicioCronometroTa + frecuenciaBloqueMs;
            DeadlinePlanificacion.iniciar(fronteraProgramadaMs - MARGEN_SEGURIDAD_MS);
            MetricasPlanificacionBloque metricasBloque = PlanificacionInstrumentacion.iniciar();
            metricasBloque.setK(k);
            metricasBloque.setSaMs(frecuenciaBloqueMs);
            metricasBloque.setInicioRealCalculo(inicioRealCalculo);
            metricasBloque.setDeadlinePublicacion(Instant.ofEpochMilli(
                    calendarioPublicaciones.tieneOrigen() ? fronteraProgramadaMs : inicioCronometroTa));
            boolean esPrimerBloque = state.getBloquesProcesados() == 0;

            verificarDetencion();
            esperarSiPausadaODetenida();
            long taEstimadoMs = estimarTaConservadorMs();
            long hundimientoMs = esPrimerBloque || esBenchmark() || !esSimulacionCincoDias()
                    ? 0L : calcularHundimientoMs(frecuenciaBloqueMs, taEstimadoMs);
            if (!esPrimerBloque && esSimulacionCincoDias() && !esBenchmark()) {
                long inicioCalculoProgramadoMs = fronteraProgramadaMs - frecuenciaBloqueMs + hundimientoMs;
                esperarHastaInstanteConCalendario(inicioCalculoProgramadoMs);
            }
            // H es coordinación previa, no forma parte de ta: el cronómetro comienza al calcular.
            inicioRealCalculo = Instant.now();
            inicioCronometroTa = inicioRealCalculo.toEpochMilli();
            metricasBloque.setInicioRealCalculo(inicioRealCalculo);
            if (!planificacionActiva.compareAndSet(false, true)) {
                throw new IllegalStateException("Ya existe una planificacion activa para " + simulacionId);
            }
            long versionCalculo = versionPlan.get();
            cancelacionCooperativaSolicitada.set(false);

            LocalDateTime ventanaInicio = state.getTiempoActual();
            LocalDateTime ventanaFin = ventanaInicio.plusMinutes(k); // K determina el salto simulado
            if (ventanaFin.isAfter(tiempoFin)) {ventanaFin = tiempoFin;}
            metricasBloque.setTiempoSimuladoInicio(ventanaInicio.toInstant(ZoneOffset.UTC).toString());
            metricasBloque.setTiempoSimuladoFin(ventanaFin.toInstant(ZoneOffset.UTC).toString());

            Instant ventanaFinUtc = ventanaFin.toInstant(ZoneOffset.UTC);
            int ciclo = state.getCicloActual() + 1;
            state.setCicloActual(ciclo);

            // --- FASE 2: EXTRACCIÓN DE CONTEXTO ---
            List<EventoBaseDTO> eventosBatch = new ArrayList<>();
            Set<String> clavesEventosPostergadosEnBatch = new HashSet<>();
            extraerEventosVueloPostergados(
                    ventanaFinUtc, eventosBatch, eventosVueloPostergados, clavesEventosPostergadosEnBatch
            );
            long inicioCargaEnvios = System.currentTimeMillis();
            List<Envio> enviosOperacionDia = esOperacionDia()
                    ? planificadorService.obtenerEnviosOperacionDiaEnVentana(ventanaInicio, ventanaFin)
                    : List.of();
            if (esOperacionDia()) {
                metricasBloque.sumarCargaEnviosMs(System.currentTimeMillis() - inicioCargaEnvios);
            }
            if (debeSaltarPlanificacionOperacionDia(enviosOperacionDia, eventosBatch, eventosVueloPostergados)) {
                procesarBloqueOperacionSinEnvios(ventanaInicio, ventanaFin, inicioCronometroTa);
                continue;
            }
            agregarEventosVuelosCancelados(ventanaInicio, ventanaFin, eventosBatch);
            Map<String, Integer> inventarioReservado = PlanificadorUtils.construirInventarioProyectado(
                    state.getEnviosEnSeguimiento(),
                    state.getEnviosEntregados(),
                    state.getInventarioSnapshot(),
                    state.getTiempoActual().toInstant(ZoneOffset.UTC)
            );

            // --- FASE 3: PLANIFICACIÓN (el paso más lento) ---
            long inicioPlanificacion = System.currentTimeMillis();
            SolucionRuta solucion = calcularSolucion(ventanaInicio, ventanaFin, inventarioReservado, enviosOperacionDia);
            long finPlanificacion = System.currentTimeMillis();
            long inicioPostprocesamiento = finPlanificacion;
            preservarAsignacionesVigentes(solucion);
            state.setSolucionActual(solucion);
            actualizarMetricasEntregaPlanificada(solucion);
            registrarEventosReplanificacion(solucion, eventosBatch, ventanaInicio, ciclo);

            long planMs = finPlanificacion - inicioPlanificacion;
            planificacionActiva.set(false);
            // --- FASE 4: MUTACIÓN FÍSICA E INDEXACIÓN DEL ESTADO ---
            long inicioAlistamientoEventos = System.currentTimeMillis();
            List<RutaAsignada> checkInsPendientes = registrarEnviosNuevos(solucion);
            metricasBloque.sumarPostprocesamientoMs(System.currentTimeMillis() - inicioPostprocesamiento);

            // --- FASE 5: GENERACIÓN Y ORDENAMIENTO DE EVENTOS EN LA VENTANA ---
            long inicioGeneracionEventos = System.currentTimeMillis();
            SimulacionEventosFactory.ResultadoEventosVuelo eventosVuelos =
                    simulacionEventosFactory.generarEventosVuelo(solucion, ventanaFinUtc);
            eventosBatch.addAll(eventosVuelos.actuales());
            agregarEventosVueloPostergados(eventosVuelos.futuros(), eventosVueloPostergados);
            eventosBatch.sort(comparadorEventos());
            metricasBloque.sumarGeneracionEventosMs(System.currentTimeMillis() - inicioGeneracionEventos);

            // --- FASE 6: CÁLCULO DE SLA Y COLAPSOS ---
            long inicioValidacion = System.currentTimeMillis();
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
            /*marcarEnviosEntregadosHasta(
                    instanteColapso != null ? instanteColapso : ventanaFinUtc,
                    eventosBatch
            );*/
            agregarAlertasAeropuertosSaturados(eventosBatch);
            eventosBatch.removeIf(evento -> evento instanceof EventoVueloDTO vuelo
                    && (vuelo.getTipo() == TipoEvento.VUELO_DESPEGA
                    || vuelo.getTipo() == TipoEvento.VUELO_ATERRIZA)
                    && vuelosCanceladosManualmente.contains(claveInstanciaVuelo(vuelo)));
            eventosBatch.sort(comparadorEventos());

            Set<String> vuelosParaIndexar = Set.copyOf(vuelosDespachadosActualizados);
            simulacionStateMutator.indexarEnviosPorVuelo(enviosDespachadosPorVuelo, vuelosParaIndexar);
            vuelosDespachadosActualizados.removeAll(vuelosParaIndexar);

            if (incumplimiento != null && colapsoCapacidad.isEmpty()) {
                registrarColapsoSla(ciclo, ventanaInicio, ventanaFin, solucion, incumplimiento, eventosBatch);
            }
            metricasBloque.sumarValidacionMs(System.currentTimeMillis() - inicioValidacion);

            long inicioPostprocesamientoFinal = System.currentTimeMillis();
            consolidarEventosVuelo(eventosBatch);

            List<EnvioDTO> enviosBatch = solucion.getAsignaciones().stream().map(e -> new EnvioDTO(e.getEnvio().getIdPedido(),
                    e.getEnvio().getOrigenIata(),e.getEnvio().getDestinoIata(),e.getEnvio().getFechaHora().toString(),
                    e.getEnvio().getCantidadMaletas(),e.getEnvio().getIdCliente(), e.getEnvio().isEsOperacionDia())).toList();
            metricasBloque.sumarPostprocesamientoMs(System.currentTimeMillis() - inicioPostprocesamientoFinal);
            long alistamientoEventosMs = System.currentTimeMillis() - inicioAlistamientoEventos;

            BloquePreparado preparado = new BloquePreparado(
                    state.getBloquesProcesados() + 1L, ventanaInicio, ventanaFin, versionCalculo,
                    Instant.ofEpochMilli(inicioCronometroTa), Instant.now(),
                    Instant.ofEpochMilli(esPrimerBloque ? System.currentTimeMillis() : fronteraProgramadaMs),
                    eventosBatch, enviosBatch, solucion, metricasBloque, planMs, alistamientoEventosMs,
                    taCalculadoDesde(inicioCronometroTa), hundimientoMs, taEstimadoMs,
                    clavesVuelosDe(solucion), new AtomicBoolean(false), new AtomicReference<>());
            if (!bloquePreparado.compareAndSet(null, preparado)) {
                planificacionActiva.set(false);
                throw new IllegalStateException("Ya existe un bloque preparado para " + simulacionId);
            }
            //planificacionActiva.set(false);

            if (cancelacionCooperativaSolicitada.get() || versionCalculo != versionPlan.get()) {
                invalidarYDescartarPreparado(preparado, "VERSION_PLAN_CAMBIO_DURANTE_CALCULO");
                state.setTiempoActual(ventanaInicio);
                PlanificacionInstrumentacion.limpiar();
                DeadlinePlanificacion.limpiar();
                continue;
            }

            // --- FASE 7: COLAPSO ---
            if (incumplimiento != null || colapsoCapacidad.isPresent()) {
                state.setTiempoActual(LocalDateTime.ofInstant(instanteColapso, ZoneOffset.UTC));
                state.guardarSnapshot();
                long inicioPublicacionColapso = System.currentTimeMillis();
                long numeroLotePublicado = publicarLote(
                        eventosBatch, enviosBatch, ventanaInicio.toInstant(ZoneOffset.UTC), ventanaFinUtc);
                metricasBloque.setNumeroLote(numeroLotePublicado);
                metricasBloque.sumarPublicacionWebSocketMs(System.currentTimeMillis() - inicioPublicacionColapso);
                long totalProcesamientoMs = System.currentTimeMillis() - inicioCronometroTa;
                completarMetricasBloque(metricasBloque, solucion, totalProcesamientoMs,
                        Instant.ofEpochMilli(inicioPublicacionColapso));
                System.out.printf("[AUDITORIA-LOTE] bloque=%d ventana=%s->%s k=%d planificacionMs=%d alistamientoEventosMs=%d totalMs=%d saMs=%d%n",
                        state.getBloquesProcesados() + 1, ventanaInicio, ventanaFin, k, planMs,
                        alistamientoEventosMs, totalProcesamientoMs, frecuenciaBloqueMs);
                state.registrarTiempoBloque(planMs, totalProcesamientoMs, frecuenciaBloqueMs);
                state.setBloquesProcesados(state.getBloquesProcesados() + 1);
                bloquePreparado.compareAndSet(preparado, null);
                publicarMetricasCapacidad(solucion);
                break;
            }

            actualizarPendientesParaSiguienteCiclo(solucion);

            // --- FASE 8: FIN DE TA Y COMPENSACIÓN DE TIEMPO (SA - TA) ---
            long taCalculadoMs = System.currentTimeMillis() - inicioCronometroTa;
            this.tiempoUltimoLoteMs = taCalculadoMs;
            metricasBloque.setFinRealCalculo(Instant.ofEpochMilli(System.currentTimeMillis()));
            ajustarSa(taCalculadoMs, state.getBloquesProcesados() + 1);

            state.setTiempoActual(ventanaFin);

            boolean esPrimerLote = state.getBloquesProcesados() == 0;
            if (esBenchmark() || esPrimerLote) {
                // Benchmark no espera y el primer lote 5D se publica apenas termina de prepararse.
            } else if (esSimulacionCincoDias()) {
                metricasBloque.setInicioEspera(Instant.now());
                esperarHastaFronteraProgramada();
            } else if (state.getTiempoActual().isBefore(tiempoFin)) {
                metricasBloque.setInicioEspera(Instant.now());
                esperarConControl();
            }

            preparado = bloquePreparado.get();
            if (!esPublicable(preparado, versionPlan.get())) {
                if (preparado != null) invalidarYDescartarPreparado(preparado, "VERSION_PLAN_OBSOLETA_ANTES_PUBLICACION");
                state.setTiempoActual(ventanaInicio);
                PlanificacionInstrumentacion.limpiar();
                DeadlinePlanificacion.limpiar();
                continue;
            }

            // --- FASE 9: ENVÍO DE DATOS A FRONTEND ---
            // El snapshot debe estar disponible antes que el lote para que las consultas de detalle
            // nunca observen el bloque siguiente que ya se está preparando.
            state.guardarSnapshot();
            long inicioPublicacion = System.currentTimeMillis();
            long numeroLotePublicado = publicarLote(
                    preparado.eventos(), preparado.envios(), ventanaInicio.toInstant(ZoneOffset.UTC), ventanaFinUtc);
            bloquePreparado.compareAndSet(preparado, null);
            metricasBloque.setNumeroLote(numeroLotePublicado);
            long publicacionMs = System.currentTimeMillis() - inicioPublicacion;
            metricasBloque.sumarPublicacionWebSocketMs(publicacionMs);
            completarMetricasBloque(metricasBloque, solucion, taCalculadoMs + publicacionMs,
                    Instant.ofEpochMilli(inicioPublicacion));
            System.out.printf("[AUDITORIA-LOTE] bloque=%d ventana=%s->%s k=%d planificacionMs=%d alistamientoEventosMs=%d totalMs=%d saMs=%d%n",
                    state.getBloquesProcesados() + 1, ventanaInicio, ventanaFin, k, planMs,
                    alistamientoEventosMs, taCalculadoMs + publicacionMs, frecuenciaBloqueMs);

            state.registrarTiempoBloque(planMs, taCalculadoMs + publicacionMs, frecuenciaBloqueMs);
            state.setBloquesProcesados(state.getBloquesProcesados() + 1);
            registrarPublicacionFisica(inicioPublicacion, metricasBloque.getFinRealCalculo(), saMs);
            registrarMetricasPreparacion(preparado, inicioPublicacion);
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
                    (anterior, nueva) -> nueva
            );
            state.getEnviosRegistrados().add(envio.getIdPedido());
            state.getUltimoAeropuertoPorEnvio().putIfAbsent(envio.getIdPedido(), envio.getOrigenIata());
            state.getEnviosConUbicacionInconsistente().remove(envio.getIdPedido());
            if (enviosConCheckIn.add(envio.getIdPedido())) {
                checkIns.add(asignacion);
            }
            if (asignacion.getItinerario() == null
                    || asignacion.getItinerario().contieneVueloCancelado()
                    || asignacion.getItinerario().getVuelos().isEmpty()
                    || asignacion.getItinerario().getVuelos().get(0).getFechaHoraSalidaUtc().isBefore(
                            state.getTiempoActual().toInstant(ZoneOffset.UTC))) {
                enviosForzadosAReplanificar.add(envio.getIdPedido());
                continue;
            }
            enviosForzadosAReplanificar.remove(envio.getIdPedido());
        }
        return checkIns;
    }

    public void refrescarContextoDatos(SimulacionContextoDatos contextoDatos) {
        this.contextoDatos = Objects.requireNonNull(contextoDatos, "El contexto de simulación es obligatorio.");
    }

    public List<VueloCancelableDTO> listarVuelosCancelables(Instant instanteRegistro) {
        SimulacionContextoDatos contexto = Objects.requireNonNull(contextoDatos, "La simulacion no tiene catalogo de vuelos");
        Map<String, Aeropuerto> aeropuertosPorCodigo = contexto.aeropuertos().stream()
                .collect(java.util.stream.Collectors.toMap(Aeropuerto::getCodigoIata, aeropuerto -> aeropuerto));
        Map<String, List<RutaAsignada>> asignacionesPorVuelo = new java.util.HashMap<>();
        state.getEnviosEnSeguimiento().values().stream()
                .filter(asignacion -> !state.getEnviosEntregados().contains(asignacion.getEnvio().getIdPedido()))
                .filter(asignacion -> asignacion.getItinerario() != null)
                .forEach(asignacion -> asignacion.getItinerario().getVuelos().forEach(vuelo ->
                        asignacionesPorVuelo.computeIfAbsent(
                                claveInstanciaVuelo(vuelo.getCodigoBase(), vuelo.getFechaHoraSalidaUtc().toString()),
                                ignorado -> new ArrayList<>()
                        ).add(asignacion)
                ));
        return contexto.vuelos().stream()
                .map(vuelo -> crearVueloCancelable(
                        vuelo, instanteRegistro, aeropuertosPorCodigo, asignacionesPorVuelo
                ))
                .filter(Objects::nonNull)
                .filter(vuelo -> !vuelo.getEnviosAfectados().isEmpty())
                .sorted(Comparator.comparing(VueloCancelableDTO::getHoraSalidaUtc))
                .toList();
    }

    public synchronized CancelacionVueloResponseDTO cancelarProximaOcurrencia(
            Long codigoVuelo,
            Instant instanteRegistro,
            String motivo
    ) {
        SimulacionContextoDatos contexto = Objects.requireNonNull(contextoDatos, "La simulacion no tiene catalogo de vuelos");
        Vuelo vueloBase = contexto.vuelos().stream()
                .filter(vuelo -> Objects.equals(vuelo.getCodigo(), codigoVuelo))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Vuelo no encontrado: " + codigoVuelo));
        VueloInstanciado instancia = SelectorCancelacionVuelo.siguienteOcurrencia(
                vueloBase, instanteRegistro, contexto.aeropuertos()
        );
        String clave = claveInstanciaVuelo(codigoVuelo, instancia.getFechaHoraSalidaUtc().toString());
        if (!vuelosCanceladosManualmente.add(clave)) {
            throw new IllegalStateException("La ocurrencia ya fue cancelada");
        }
        if (vueloYaDespachadoAlInstante(
                enviosDespachadosPorVuelo.containsKey(clave),
                instancia.getFechaHoraSalidaUtc(),
                instanteRegistro)) {
            vuelosCanceladosManualmente.remove(clave);
            throw new IllegalStateException("La ocurrencia ya fue despachada");
        }

        List<RutaAsignada> asignacionesAfectadas = asignacionesDeInstancia(clave);
        List<String> idsAfectados = asignacionesAfectadas.stream()
                .map(asignacion -> asignacion.getEnvio().getIdPedido())
                .distinct()
                .toList();
        int maletasAfectadas = asignacionesAfectadas.stream()
                .map(RutaAsignada::getEnvio)
                .collect(java.util.stream.Collectors.toMap(
                        Envio::getIdPedido, Envio::getCantidadMaletas, (actual, ignorado) -> actual
                ))
                .values().stream().mapToInt(Integer::intValue).sum();

        for (RutaAsignada asignacion : asignacionesAfectadas) {
            asignacion.getItinerario().getVuelos().stream()
                    .filter(vuelo -> clave.equals(claveInstanciaVuelo(
                            vuelo.getCodigoBase(), vuelo.getFechaHoraSalidaUtc().toString()
                    )))
                    .forEach(vuelo -> {
                        vuelo.setCanceladoPorIncidencia(true);
                        vuelo.setMotivoCancelacion("CANCELACION_MANUAL");
                    });
        }
        marcarEnviosParaReplanificar(new LinkedHashSet<>(idsAfectados), "VUELO_CANCELADO");
        invalidarPreparacionSiCorresponde(clave, idsAfectados);

        EventoVueloDTO evento = simulacionEventosFactory.crearEventoVuelo(instancia, TipoEvento.VUELO_CANCELADO);
        evento.setFechaHoraEvento(instanteRegistro.toString());
        evento.setMotivo(motivo == null || motivo.isBlank() ? "CANCELACION_MANUAL" : motivo);
        evento.setCodigoEnvios(idsAfectados);
        evento.setCantidadMaletas(maletasAfectadas);
        evento.setCapacidadMax(instancia.getCapacidadMax());
        webSocketPublisher.publicarLote(simulacionId, new LoteEventosDTO(
                simulacionId, state.siguienteLote(), instanteRegistro.toString(), instanteRegistro.toString(),
                1, List.of(evento), List.of(), saMs, versionPlan.get(), null
        ));

        return new CancelacionVueloResponseDTO(
                codigoVuelo, instancia.getOrigenIata(), instancia.getDestinoIata(), instanteRegistro.toString(),
                instancia.getFechaHoraSalida().toString(), instancia.getFechaHoraSalidaUtc().toString(),
                idsAfectados, maletasAfectadas, "REGISTRADA"
        );
    }

    private VueloCancelableDTO crearVueloCancelable(
            Vuelo vuelo,
            Instant instanteRegistro,
            Map<String, Aeropuerto> aeropuertos,
            Map<String, List<RutaAsignada>> asignacionesPorVuelo
    ) {
        VueloInstanciado instancia = SelectorCancelacionVuelo.siguienteOcurrencia(vuelo, instanteRegistro, aeropuertos);
        if (horaFin != null && !instancia.getFechaHoraSalidaUtc().isBefore(horaFin.toInstant(ZoneOffset.UTC))) {
            return null;
        }
        String clave = claveInstanciaVuelo(vuelo.getCodigo(), instancia.getFechaHoraSalidaUtc().toString());
        if (vuelosCanceladosManualmente.contains(clave)) {
            return null;
        }
        List<RutaAsignada> asignaciones = asignacionesPorVuelo.getOrDefault(clave, List.of());
        List<String> ids = asignaciones.stream().map(a -> a.getEnvio().getIdPedido()).distinct().toList();
        int maletas = asignaciones.stream()
                .map(RutaAsignada::getEnvio)
                .collect(java.util.stream.Collectors.toMap(
                        Envio::getIdPedido, Envio::getCantidadMaletas, (actual, ignorado) -> actual
                )).values().stream().mapToInt(Integer::intValue).sum();
        return new VueloCancelableDTO(
                vuelo.getCodigo(), vuelo.getOrigenIata(), vuelo.getDestinoIata(),
                instancia.getFechaHoraSalida().toString(), instancia.getFechaHoraSalidaUtc().toString(),
                instancia.getFechaHoraLlegada().toString(), instancia.getFechaHoraLlegadaUtc().toString(),
                vuelo.getCapacidadMax(), ids, maletas
        );
    }

    private List<RutaAsignada> asignacionesDeInstancia(String clave) {
        return state.getEnviosEnSeguimiento().values().stream()
                .filter(asignacion -> !state.getEnviosEntregados().contains(asignacion.getEnvio().getIdPedido()))
                .filter(asignacion -> asignacion.getItinerario() != null)
                .filter(asignacion -> asignacion.getItinerario().getVuelos().stream().anyMatch(vuelo ->
                        clave.equals(claveInstanciaVuelo(
                                vuelo.getCodigoBase(), vuelo.getFechaHoraSalidaUtc().toString()
                        ))
                ))
                .toList();
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
            // Antes del despacho la ruta es una reserva, no un compromiso físico.
            // Conservarla impedía reemplazar itinerarios vencidos y provocaba colapsos SLA evitables.
            if (!envioFueDespachadoEnPrimerVuelo(anterior)) {
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
                .filter(incumplimiento -> enviosForzadosAReplanificar.contains(
                                incumplimiento.asignacion().getEnvio().getIdPedido())
                        || incumplimiento.asignacion().getItinerario() == null
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
            if (vuelosCanceladosManualmente.contains(claveInstanciaVuelo(evento))) {
                iterator.remove();
                continue;
            }
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
            if (vuelosCanceladosManualmente.contains(claveInstanciaVuelo(eventoVuelo))) {
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
                .filter(evento -> !vuelosCanceladosManualmente.contains(claveInstanciaVuelo(evento)))
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
        state.getUltimoAeropuertoPorEnvio().put(envio.getIdPedido(), envio.getOrigenIata());
        state.getEnviosConUbicacionInconsistente().remove(envio.getIdPedido());
        state.registrarEnvioEnAlmacen(envio.getOrigenIata(), envio.getIdPedido());
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
            String claveVuelo = claveInstanciaVuelo(evento);
            if (enviosDespachadosPorVuelo.containsKey(claveVuelo)) {
                return Optional.empty();
            }
            String origen = evento.getOrigenIata();
            int inventarioDisponible = state.getInventarioSnapshot().getOrDefault(origen, 0);
            if (evento.getCantidadMaletas() > inventarioDisponible) {
                ajustarCargaEvento(evento, inventarioDisponible);
            }
            int maletasCargadas = registrarCargaRealDespachada(evento, inventarioDisponible);
            if (maletasCargadas != evento.getCantidadMaletas()) {
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
            String claveVuelo = claveInstanciaVuelo(evento);
            if (!aterrizajesProcesados.add(claveVuelo)) {
                return Optional.empty();
            }
            aplicarCargaRealDespachada(evento);
            String destino = evento.getDestinoIata();
            int entregadasEnDestino = registrarEntregasDirectas(evento, horaEvento);
            actualizarUbicacionTrasLlegada(evento);
            registrarLlegadasEnAlmacen(evento, destino);
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
        int limiteVuelo = evento.getCapacidadMax() > 0 ? evento.getCapacidadMax() : Integer.MAX_VALUE;
        int cargaReal = Math.min(limiteVuelo, Math.max(inventarioDisponible, 0));
        Set<String> enviosEsperados = obtenerEnviosEsperadosEnVuelo(evento);
        Set<String> enviosUbicacionDesconocida = enviosEsperados.stream()
                .filter(id -> state.getUltimoAeropuertoPorEnvio().get(id) == null)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!enviosUbicacionDesconocida.isEmpty()) {
            state.getEnviosConUbicacionInconsistente().addAll(enviosUbicacionDesconocida);
            conservarComoPendientesSinAlterarRuta(enviosUbicacionDesconocida);
            System.err.println("[INCONSISTENCIA-UBICACION] envios=" + enviosUbicacionDesconocida.size()
                    + " accion=NO_PROCESAR");
        }
        Set<String> enviosCargados = seleccionarEnviosCargados(evento, cargaReal);
        Set<String> enviosNoCargados = new LinkedHashSet<>(enviosEsperados);
        enviosNoCargados.removeAll(enviosCargados);
        enviosNoCargados.removeAll(enviosUbicacionDesconocida);
        marcarEnviosParaReplanificar(enviosNoCargados, "NO_ABORDO_VUELO");
        int maletasCargadas = enviosCargados.stream()
                .map(state.getEnviosEnSeguimiento()::get)
                .filter(java.util.Objects::nonNull)
                .mapToInt(asignacion -> asignacion.getEnvio().getCantidadMaletas())
                .sum();
        String clave = claveInstanciaVuelo(evento);
        maletasDespachadasPorVuelo.put(clave, maletasCargadas);
        enviosDespachadosPorVuelo.put(clave, enviosCargados);
        enviosCargados.forEach(idPedido -> {
            RutaAsignada asignacion = state.getEnviosEnSeguimiento().get(idPedido);
            if (asignacion != null) {
                state.retirarEnvioDeAlmacen(evento.getOrigenIata(), idPedido);
            }
        });
        evento.setCodigoEnvios(new ArrayList<>(enviosCargados));
        vuelosDespachadosActualizados.add(clave);
        return maletasCargadas;
    }

    private synchronized void conservarComoPendientesSinAlterarRuta(Set<String> idsPedidos) {
        Map<String, Envio> pendientes = new LinkedHashMap<>();
        state.getEnviosPendientes().forEach(envio -> pendientes.put(envio.getIdPedido(), envio));
        idsPedidos.stream()
                .map(state.getEnviosEnSeguimiento()::get)
                .filter(Objects::nonNull)
                .map(RutaAsignada::getEnvio)
                .forEach(envio -> pendientes.put(envio.getIdPedido(), envio));
        state.setEnviosPendientes(new ArrayList<>(pendientes.values()));
    }

    private Set<String> seleccionarEnviosCargados(EventoVueloDTO evento, int cargaMaxima) {
        Set<String> enviosCargados = new LinkedHashSet<>();
        int restante = Math.max(cargaMaxima, 0);
        List<RutaAsignada> asignaciones = state.getEnviosEnSeguimiento().values().stream()
                .filter(asignacion -> asignacion.getItinerario() != null)
                .filter(asignacion -> !state.getEnviosEntregados().contains(asignacion.getEnvio().getIdPedido()))
                .filter(asignacion -> contieneVuelo(asignacion, evento))
                .filter(asignacion -> evento.getOrigenIata().equals(
                        state.getUltimoAeropuertoPorEnvio().get(asignacion.getEnvio().getIdPedido())))
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
        if(asignaciones.size()!=enviosCargados.size()){
            System.out.println("[REPLANIF-VUELO] asignaciones = "+asignaciones.size()
                    +", enviosCargados = "+enviosCargados.size()
                    + "");
        }
        return enviosCargados;
    }

    private Set<String> obtenerEnviosEsperadosEnVuelo(EventoVueloDTO evento) {
        return state.getEnviosEnSeguimiento().values().stream()
                .filter(asignacion -> asignacion.getItinerario() != null)
                .filter(asignacion -> !state.getEnviosEntregados().contains(asignacion.getEnvio().getIdPedido()))
                .filter(asignacion -> contieneVuelo(asignacion, evento))
                .map(asignacion -> asignacion.getEnvio().getIdPedido())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private synchronized void marcarEnviosParaReplanificar(Set<String> idsPedidos, String motivo) {
        if (idsPedidos.isEmpty()) return;
        Map<String, Envio> pendientes = new LinkedHashMap<>();
        state.getEnviosPendientes().forEach(envio -> pendientes.put(envio.getIdPedido(), envio));
        for (String idPedido : idsPedidos) {
            if (state.getEnviosEntregados().contains(idPedido)) continue;
            RutaAsignada asignacion = state.getEnviosEnSeguimiento().get(idPedido);
            if (asignacion == null) continue;
            enviosForzadosAReplanificar.add(idPedido);
            asignacion.setItinerario(null);
            pendientes.put(idPedido, asignacion.getEnvio());
            System.out.println("[REINTENTO-ENVIO] idPedido=" + idPedido + " motivo=" + motivo);
        }
        state.setEnviosPendientes(new ArrayList<>(pendientes.values()));
    }

    private boolean contieneVuelo(RutaAsignada asignacion, EventoVueloDTO evento) {
        return asignacion.getItinerario().getVuelos().stream()
                .anyMatch(vuelo -> vuelo.getCodigoBase().equals(evento.getCodigoVuelo())
                        && vuelo.getFechaHoraSalidaUtc().toString().equals(evento.getHoraSalidaUtc()));
    }

    private void aplicarCargaRealDespachada(EventoVueloDTO evento) {
        Integer cargaReal = maletasDespachadasPorVuelo.get(claveInstanciaVuelo(evento));
        Set<String> enviosDespachados = enviosDespachadosPorVuelo.getOrDefault(
                claveInstanciaVuelo(evento), Set.of()
        );
        evento.setCodigoEnvios(new ArrayList<>(enviosDespachados));
        if (cargaReal != null && cargaReal != evento.getCantidadMaletas()) {
            ajustarCargaEvento(evento, cargaReal);
        }
    }

    private void ajustarCargaEvento(EventoVueloDTO evento, int cantidadMaletas) {
        int cantidadAjustada = Math.max(cantidadMaletas, 0);
        evento.setCantidadMaletas(cantidadAjustada);
        int capacidad = evento.getCapacidadMax();
        double porcentaje = capacidad > 0 ? (cantidadAjustada * 100.0) / capacidad : 0.0;
        evento.setPorcentajeOcupacion(porcentaje);
        evento.setEstado(SimulacionEventosFactory.calcularEstadoAeropuerto(cantidadAjustada, capacidad));
    }

    private int registrarEntregasDirectas(EventoVueloDTO evento, Instant horaEvento) {
        int entregadas = 0;
        Set<String> enviosDespachados = enviosDespachadosPorVuelo.get(claveInstanciaVuelo(evento));
        if (enviosDespachados == null || enviosDespachados.isEmpty()) {
            return 0;
        }
        for (String idPedido : enviosDespachados) {
            RutaAsignada asignacion = state.getEnviosEnSeguimiento().get(idPedido);
            if (asignacion == null || asignacion.getItinerario() == null
                    || state.getEnviosEntregados().contains(idPedido)) {
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

    private void actualizarUbicacionTrasLlegada(EventoVueloDTO evento) {
        Set<String> enviosDespachados = enviosDespachadosPorVuelo.getOrDefault(
                claveInstanciaVuelo(evento), Set.of()
        );
        enviosDespachados.forEach(idPedido ->
                state.getUltimoAeropuertoPorEnvio().put(idPedido, evento.getDestinoIata())
        );
    }

    private void registrarLlegadasEnAlmacen(EventoVueloDTO evento, String destino) {
        Set<String> enviosDespachados = enviosDespachadosPorVuelo.getOrDefault(
                claveInstanciaVuelo(evento), Set.of()
        );
        for (String idPedido : enviosDespachados) {
            if (state.getEnviosEntregados().contains(idPedido)) continue;
            RutaAsignada asignacion = state.getEnviosEnSeguimiento().get(idPedido);
            if (asignacion != null) {
                state.registrarEnvioEnAlmacen(destino, idPedido);
            }
        }
    }

    private static String claveInstanciaVuelo(EventoVueloDTO evento) {
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
                //simulacionStateMutator.restarMaletas(destino, asignacion.getEnvio().getCantidadMaletas());
                state.retirarEnvioDeAlmacen(destino, idPedido);
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
        return enviosDespachados != null && enviosDespachados.contains(idPedido);
    }

    private static String claveInstanciaVuelo(Long codigoVuelo, String horaSalidaUtc) {
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
        Map<String, Envio> pendientesOriginales = state.getEnviosPendientes().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Envio::getIdPedido, envio -> envio, (actual, ignorado) -> actual, LinkedHashMap::new
                ));
        List<Envio> pendientesParaPlanificar = state.getEnviosPendientes().stream()
                .map(this::copiarEnvioDesdeUbicacionActual)
                .filter(Objects::nonNull)
                .toList();
        SolucionRuta solucion;
        if (contextoDatos == null) {
            solucion = esOperacionDia()
                    ? planificadorService.calcularSolucionOperacionDia(
                            algoritmo, ventanaInicio, ventanaFin, pendientesParaPlanificar, inventarioReservado,
                            enviosOperacionDia
                    )
                    : planificadorService.calcularSolucion(
                            algoritmo, ventanaInicio, ventanaFin, pendientesParaPlanificar, inventarioReservado
                    );
        } else {
            solucion = esOperacionDia()
                ? planificadorService.calcularSolucionOperacionDia(
                        algoritmo, ventanaInicio, ventanaFin, pendientesParaPlanificar, inventarioReservado,
                        enviosOperacionDia, contextoDatos.vuelos(), contextoDatos.aeropuertos(),
                        contextoDatos.incidencias()
                )
                : planificadorService.calcularSolucion(
                        algoritmo, ventanaInicio, ventanaFin, pendientesParaPlanificar, inventarioReservado,
                        contextoDatos.vuelos(), contextoDatos.aeropuertos(), contextoDatos.incidencias(),
                        Set.copyOf(vuelosCanceladosManualmente),
                        calcularCargaReservadaPorVuelo(ventanaInicio.toInstant(ZoneOffset.UTC))
                );
        }
        solucion.getAsignaciones().forEach(asignacion -> {
            Envio original = pendientesOriginales.get(asignacion.getEnvio().getIdPedido());
            if (original != null) asignacion.setEnvio(original);
        });
        return solucion;
    }

    private Envio copiarEnvioDesdeUbicacionActual(Envio original) {
        Envio copia = new Envio();
        copia.setIdPedido(original.getIdPedido());
        String ubicacion = state.getUltimoAeropuertoPorEnvio().get(original.getIdPedido());
        if (ubicacion == null) {
            state.getEnviosConUbicacionInconsistente().add(original.getIdPedido());
            System.err.println("[INCONSISTENCIA-UBICACION] envios=1 accion=OMITIR_REPLANIFICACION");
            return null;
        }
        copia.setOrigenIata(ubicacion);
        copia.setDestinoIata(original.getDestinoIata());
        copia.setFechaHora(original.getFechaHora());
        copia.setCantidadMaletas(original.getCantidadMaletas());
        copia.setIdCliente(original.getIdCliente());
        copia.setActivo(original.isActivo());
        copia.setEsOperacionDia(original.isEsOperacionDia());
        copia.setFromOperaciones(original.isFromOperaciones());
        return copia;
    }

    private Map<String, Integer> calcularCargaReservadaPorVuelo(Instant referencia) {
        Set<String> pendientes = state.getEnviosPendientes().stream()
                .map(Envio::getIdPedido)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Integer> cargaReservada = new HashMap<>();
        for (RutaAsignada asignacion : state.getEnviosEnSeguimiento().values()) {
            String idPedido = asignacion.getEnvio().getIdPedido();
            if (asignacion.getItinerario() == null
                    || pendientes.contains(idPedido)
                    || state.getEnviosEntregados().contains(idPedido)
                    || asignacion.getItinerario().getVuelos().stream().anyMatch(vuelo ->
                            vuelo.isEstaCancelado() || vuelosCanceladosManualmente.contains(claveInstanciaVuelo(
                                    vuelo.getCodigoBase(), vuelo.getFechaHoraSalidaUtc().toString()
                            )))) {
                continue;
            }
            int cantidad = asignacion.getEnvio().getCantidadMaletas();
            for (var vuelo : asignacion.getItinerario().getVuelos()) {
                if (!vuelo.getFechaHoraSalidaUtc().isBefore(referencia)) {
                    cargaReservada.merge(
                            claveInstanciaVuelo(vuelo.getCodigoBase(), vuelo.getFechaHoraSalidaUtc().toString()),
                            cantidad,
                            Integer::sum
                    );
                }
            }
        }
        return cargaReservada;
    }

    private synchronized void actualizarPendientesParaSiguienteCiclo(SolucionRuta solucion) {
        Map<String, Envio> pendientes = new LinkedHashMap<>();
        solucion.obtenerEnviosConConflictos().forEach(envio -> pendientes.put(envio.getIdPedido(), envio));
        for (String idPedido : enviosForzadosAReplanificar) {
            RutaAsignada asignacion = state.getEnviosEnSeguimiento().get(idPedido);
            if (asignacion != null && !state.getEnviosEntregados().contains(idPedido)) {
                pendientes.put(idPedido, asignacion.getEnvio());
            }
        }
        state.setEnviosPendientes(new ArrayList<>(pendientes.values()));
        if (!state.getEnviosPendientes().isEmpty()) {
            System.out.println("[SIMULADOR] enviosPendientes=" + state.getEnviosPendientes().size());
        }
    }

    private void ajustarSa(long taMs, long bloque) {
        if (esOperacionDia() || saMs >= SA_MAXIMO_MS) {
            return;
        }
        int umbralMs = saMs - 1_000;
        if (taMs < umbralMs) {
            bloquesConsecutivosCercaLimite = 0;
            return;
        }
        bloquesConsecutivosCercaLimite++;
        if (bloquesConsecutivosCercaLimite < 2) {
            return;
        }

        int anterior = saMs;
        saMs = Math.min(SA_MAXIMO_MS, saMs + 1_000);
        bloquesConsecutivosCercaLimite = 0;
        state.getHistorialAjustesSa().add(
                "bloque=" + bloque + " taMs=" + taMs + " saAnteriorMs=" + anterior + " saNuevoMs=" + saMs
        );
        System.out.printf("[SA-ADAPTATIVO] bloque=%d taMs=%d umbralMs=%d saAnteriorMs=%d saNuevoMs=%d%n",
                bloque, taMs, umbralMs, anterior, saMs);
    }

    private boolean envioFueDespachadoEnPrimerVuelo(RutaAsignada asignacion) {
        var vuelos = asignacion.getItinerario().getVuelos();
        if (vuelos.isEmpty()) {
            return false;
        }
        var primerVuelo = vuelos.get(0);
        Set<String> enviosDespachados = enviosDespachadosPorVuelo.get(claveInstanciaVuelo(
                primerVuelo.getCodigoBase(), primerVuelo.getFechaHoraSalidaUtc().toString()
        ));
        return enviosDespachados != null && enviosDespachados.contains(asignacion.getEnvio().getIdPedido());
    }

    private void actualizarMetricasEntregaPlanificada(SolucionRuta solucion) {
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) {
                continue;
            }
            long minutos = Duration.between(
                    PlanificadorUtils.obtenerFechaIngresoUtc(asignacion.getEnvio()),
                    asignacion.getItinerario().getFechaHoraLlegadaUtc()
            ).toMinutes();
            if (minutos >= 0) {
                state.getMinutosEntregaPlanificadaPorEnvio().put(asignacion.getEnvio().getIdPedido(), minutos);
            }
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
        state.setTiempoActual(ventanaFin);
        EventoBaseDTO eventoActivo = new EventoEstadoSimulacionDTO(
                TipoEvento.OPERACION_DIA_ACTIVA,
                ventanaInicio.toInstant(ZoneOffset.UTC).toString(),
                simulacionId,
                horaInicio.toString(),
                "EN_EJECUCION",
                "Operacion activa. Registra envios para iniciar la planificacion."
        );
        long numeroLotePublicado = publicarLote(
                List.of(eventoActivo),
                List.of(),
                ventanaInicio.toInstant(ZoneOffset.UTC),
                ventanaFin.toInstant(ZoneOffset.UTC)
        );
        MetricasPlanificacionBloque metricas = PlanificacionInstrumentacion.actual();
        if (metricas != null) {
            metricas.setNumeroLote(numeroLotePublicado);
            metricas.setFinRealCalculo(Instant.now());
            completarMetricasBloque(metricas, state.getSolucionActual(), taCalculadoMs, Instant.now());
        }
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

    private boolean esBenchmark() {
        return "BENCHMARK".equalsIgnoreCase(modo);
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

    private long publicarLote(List<EventoBaseDTO> eventos, List<EnvioDTO> envios, Instant ventanaInicio, Instant ventanaFin) {
        long numeroLote = state.siguienteLote();
        LoteEventosDTO lote = new LoteEventosDTO(
                simulacionId,
                numeroLote,
                ventanaInicio != null ? ventanaInicio.toString() : null,
                ventanaFin != null ? ventanaFin.toString() : null,
                eventos.size(),
                eventos,
                envios,
                saMs,
                versionPlan.get(),
                ventanaInicio != null && ventanaFin != null && !ventanaInicio.equals(ventanaFin)
                        ? state.getBloquesProcesados() + 1L : null
        );
        state.setUltimoLoteEmitido(lote);
        webSocketPublisher.publicarLote(simulacionId, lote);
        return numeroLote;
    }

    private void completarMetricasBloque(
            MetricasPlanificacionBloque metricas,
            SolucionRuta solucion,
            long taTotalMs,
            Instant inicioPublicacion
    ) {
        Instant ahora = Instant.now();
        if (metricas.getFinRealCalculo() == null) metricas.setFinRealCalculo(ahora);
        metricas.setPublicacion(ahora);
        if (metricas.getInicioEspera() == null && inicioPublicacion != null
                && inicioPublicacion.isAfter(metricas.getFinRealCalculo())) {
            metricas.setInicioEspera(metricas.getFinRealCalculo());
        }
        metricas.setTaTotalMs(Math.max(0, taTotalMs));
        metricas.setTaSuperoSa(taTotalMs > metricas.getSaMs());

        int planificados = 0;
        int sinItinerario = 0;
        int directos = 0;
        int conEscala = 0;
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) {
                sinItinerario++;
            } else {
                planificados++;
                if (asignacion.getItinerario().getCantidadVuelos() == 1) directos++;
                else if (asignacion.getItinerario().getCantidadVuelos() > 1) conEscala++;
            }
        }
        metricas.setPlanificados(planificados);
        metricas.setSinItinerario(sinItinerario);
        metricas.setDirectos(directos);
        metricas.setConEscala(conEscala);
        state.getMetricasPlanificacionPorBloque().add(metricas);

        System.out.printf(
                "[METRICAS-BLOQUE] lote=%d k=%d saFMs=%d simInicio=%s simFin=%s "
                        + "calculoInicio=%s calculoFin=%s esperaInicio=%s publicacion=%s deadline=%s "
                        + "taMs=%d taSuperoSa=%s cargaEnviosMs=%d vuelosMs=%d itinerariosMs=%d "
                        + "construccionMs=%d graspMs=%d tabuMs=%d validacionMs=%d postMs=%d "
                        + "eventosMs=%d websocketMs=%d nuevos=%d pendientes=%d planificados=%d "
                        + "sinItinerario=%d directos=%d conEscala=%d candidatos=%d "
                        + "candidatosDirectos=%d candidatosEscala=%d proporcionEscala=%.4f "
                        + "directosAntesRecorte=%d escalasAntesRecorte=%d directosDescartados=%d "
                        + "escalasDescartadas=%d conDirectaUsaronEscala=%d sinDirectaResueltosEscala=%d "
                        + "generacionEscalasMs=%d timeout=%s deadlineAlcanzado=%s faseDeadline=%s "
                        + "enviosNoProcesados=%d mejorFitness=%.4f escalasDominadas=%d escalasConservadas=%d "
                        + "candidatosAntesPoda=%d candidatosDespuesPoda=%d tiempoPodaMs=%d%n",
                metricas.getNumeroLote(), metricas.getK(), metricas.getSaMs(),
                metricas.getTiempoSimuladoInicio(), metricas.getTiempoSimuladoFin(),
                metricas.getInicioRealCalculo(), metricas.getFinRealCalculo(), metricas.getInicioEspera(),
                metricas.getPublicacion(), metricas.getDeadlinePublicacion(), metricas.getTaTotalMs(),
                metricas.isTaSuperoSa(), metricas.getCargaEnviosMs(), metricas.getGeneracionVuelosMs(),
                metricas.getGeneracionItinerariosMs(), metricas.getConstruccionInicialMs(),
                metricas.getGraspMs(), metricas.getTabuMs(), metricas.getValidacionMs(),
                metricas.getPostprocesamientoMs(), metricas.getGeneracionEventosMs(),
                metricas.getPublicacionWebSocketMs(), metricas.getEnviosNuevos(), metricas.getPendientes(),
                metricas.getPlanificados(), metricas.getSinItinerario(), metricas.getDirectos(),
                metricas.getConEscala(), metricas.getCandidatosGenerados(), metricas.getCandidatosDirectos(),
                metricas.getCandidatosConEscala(), metricas.getProporcionCandidatosConEscala(),
                metricas.getCandidatosDirectosAntesRecorte(), metricas.getCandidatosConEscalaAntesRecorte(),
                metricas.getCandidatosDirectosDescartados(), metricas.getCandidatosConEscalaDescartados(),
                metricas.getEnviosConDirectaQueUsaronEscala(), metricas.getEnviosSinDirectaResueltosConEscala(),
                metricas.getGeneracionEscalasMs(),
                metricas.isTimeoutAlcanzado(), metricas.isDeadlineAlcanzado(), metricas.getFaseDeadline(),
                metricas.getEnviosNoProcesados(), metricas.getMejorFitnessConocido(),
                metricas.getEscalasDominadasEliminadas(), metricas.getEscalasConservadas(),
                metricas.getCandidatosAntesPoda(), metricas.getCandidatosDespuesPoda(),
                metricas.getTiempoPodaEscalasMs()
        );
            PlanificacionInstrumentacion.limpiar();
            DeadlinePlanificacion.limpiar();
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

    private void esperarHastaFronteraProgramada() {
        while (System.currentTimeMillis() < calendarioPublicaciones.getFronteraProgramadaMs()) {
            verificarDetencion();
            esperarSiPausadaODetenida();
            long restante = calendarioPublicaciones.getFronteraProgramadaMs() - System.currentTimeMillis();
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

    private void esperarHastaInstanteConCalendario(long instanteObjetivoMs) {
        while (System.currentTimeMillis() < instanteObjetivoMs) {
            verificarDetencion();
            esperarSiPausadaODetenida();
            long restante = instanteObjetivoMs - System.currentTimeMillis();
            if (restante <= 0) return;
            dormir(Math.min(restante, 250L));
        }
    }

    static long calcularHundimientoMs(long frecuenciaMs, long taEstimadoMs) {
        long maximo = Math.max(0L, frecuenciaMs / 2L);
        return Math.max(0L, Math.min(maximo,
                frecuenciaMs - Math.max(0L, taEstimadoMs) - MARGEN_SEGURIDAD_MS));
    }

    private long estimarTaConservadorMs() {
        List<MetricasPlanificacionBloque> historial = state.getMetricasPlanificacionPorBloque();
        if (historial.isEmpty()) return Math.max(1L, saMs / 2L);
        List<Long> muestras = historial.stream()
                .skip(Math.max(0, historial.size() - 10L))
                .map(MetricasPlanificacionBloque::getTaTotalMs)
                .sorted()
                .toList();
        int indiceP90 = Math.min(muestras.size() - 1, (int) Math.ceil(muestras.size() * 0.90) - 1);
        return Math.max(1L, muestras.get(Math.max(0, indiceP90)));
    }

    private long taCalculadoDesde(long inicioMs) {
        return Math.max(0L, System.currentTimeMillis() - inicioMs);
    }

    private Set<String> clavesVuelosDe(SolucionRuta solucion) {
        Set<String> claves = new HashSet<>();
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;
            asignacion.getItinerario().getVuelos().forEach(vuelo -> claves.add(claveInstanciaVuelo(
                    vuelo.getCodigoBase(), vuelo.getFechaHoraSalidaUtc().toString())));
        }
        return Set.copyOf(claves);
    }

    private void invalidarPreparacionSiCorresponde(String claveVuelo, List<String> idsAfectados) {
        BloquePreparado preparado = bloquePreparado.get();
        boolean relevantePreparado = cancelacionAfecta(preparado, claveVuelo, idsAfectados);
        boolean relevanteEnCalculo = preparado == null && planificacionActiva.get() && !idsAfectados.isEmpty();
        if (!relevantePreparado && !relevanteEnCalculo) return;

        long nuevaVersion = versionPlan.incrementAndGet();
        cancelacionCooperativaSolicitada.set(true);
        if (preparado != null) {
            preparado.invalidado().set(true);
            preparado.causaInvalidacion().compareAndSet(null, "CANCELACION_RELEVANTE");
        }
        System.out.printf("[BLOQUE-INVALIDADO] versionNueva=%d preparado=%s calculoActivo=%s causa=CANCELACION_RELEVANTE%n",
                nuevaVersion, preparado != null, planificacionActiva.get());
    }

    static boolean cancelacionAfecta(
            BloquePreparado preparado, String claveVuelo, List<String> idsAfectados) {
        return preparado != null && (preparado.clavesVuelos().contains(claveVuelo)
                || preparado.eventos().stream()
                .filter(EventoVueloDTO.class::isInstance)
                .map(EventoVueloDTO.class::cast)
                .anyMatch(evento -> claveVuelo.equals(claveInstanciaVuelo(evento)))
                || preparado.envios().stream().anyMatch(envio -> idsAfectados.contains(envio.getIdPedido())));
    }

    /**
     * La generación anticipada aplica la física para construir el bloque preparado. Ese registro
     * no equivale a que el vuelo ya haya despegado respecto del reloj visible de la solicitud.
     */
    static boolean vueloYaDespachadoAlInstante(
            boolean registradoComoDespachado, Instant salidaUtc, Instant instanteSolicitud) {
        return registradoComoDespachado && !salidaUtc.isAfter(instanteSolicitud);
    }

    static boolean esPublicable(BloquePreparado preparado, long versionActual) {
        return preparado != null && !preparado.invalidado().get() && preparado.versionPlan() == versionActual;
    }

    private void invalidarYDescartarPreparado(BloquePreparado preparado, String causa) {
        preparado.invalidado().set(true);
        preparado.causaInvalidacion().compareAndSet(null, causa);
        bloquePreparado.compareAndSet(preparado, null);
        System.out.printf("[BLOQUE-DESCARTADO] bloqueFisico=%d versionPlan=%d causa=%s%n",
                preparado.indiceFisico(), preparado.versionPlan(), preparado.causaInvalidacion().get());
    }

    private void registrarMetricasPreparacion(BloquePreparado preparado, long publicacionRealMs) {
        long preparadoMs = Math.max(0L, publicacionRealMs - preparado.finCalculo().toEpochMilli());
        long atrasoMs = Math.max(0L, publicacionRealMs - preparado.fronteraPublicacion().toEpochMilli());
        System.out.printf("[BLOQUE-PREPARADO] bloqueFisico=%d versionPlan=%d hMs=%d taEstimadoMs=%d "
                        + "calculoInicio=%s calculoFin=%s frontera=%s preparadoMs=%d publicacion=%s atrasoMs=%d "
                        + "invalidado=%s causaInvalidacion=%s%n",
                preparado.indiceFisico(), preparado.versionPlan(), preparado.hundimientoMs(),
                preparado.taEstimadoMs(), preparado.inicioCalculo(), preparado.finCalculo(),
                preparado.fronteraPublicacion(), preparadoMs, Instant.ofEpochMilli(publicacionRealMs), atrasoMs,
                preparado.invalidado().get(), preparado.causaInvalidacion().get());
    }

    private void registrarPublicacionFisica(long publicacionRealMs, Instant finCalculo, int frecuenciaSiguienteMs) {
        long finCalculoMs = finCalculo != null ? finCalculo.toEpochMilli() : publicacionRealMs;
        RegistroPublicacion registro = calendarioPublicaciones.registrarPublicacion(
                publicacionRealMs, finCalculoMs, frecuenciaSiguienteMs);
        System.out.printf(
                "[CALENDARIO-PUBLICACION] bloqueFisico=%d origen=%s frontera=%s publicacionReal=%s "
                        + "fIntervaloMs=%d atrasoMs=%d adelantoAntesEsperaMs=%d derivaAcumuladaMs=%d "
                        + "tiempoPausadoMs=%d fronterasIncumplidas=%d%n",
                registro.indiceBloqueFisico(), Instant.ofEpochMilli(registro.origenPublicacionesMs()),
                Instant.ofEpochMilli(registro.fronteraProgramadaMs()),
                Instant.ofEpochMilli(registro.publicacionRealMs()), registro.frecuenciaIntervaloMs(),
                registro.atrasoMs(), registro.adelantoAntesEsperaMs(), registro.derivaAcumuladaMs(),
                registro.tiempoPausadoAcumuladoMs(), registro.fronterasIncumplidas());
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
        calendarioPublicaciones.iniciarPausa(System.currentTimeMillis());
        pausada.set(true);
        state.setEstado("PAUSADA");
        publicarControl(TipoEvento.SIMULACION_PAUSADA);
    }

    public void reanudar() {
        if (esTerminal()) return;
        long duracionPausaMs = calendarioPublicaciones.finalizarPausa(System.currentTimeMillis());
        pausada.set(false);
        state.setEstado("EN_PROCESO");
        if (duracionPausaMs > 0) {
            System.out.printf("[CALENDARIO-PAUSA] inicio=%s fin=%s duracionMs=%d ajusteAcumuladoMs=%d origenAjustado=%s%n",
                    Instant.ofEpochMilli(calendarioPublicaciones.getUltimoInicioPausaMs()),
                    Instant.ofEpochMilli(calendarioPublicaciones.getUltimoFinPausaMs()), duracionPausaMs,
                    calendarioPublicaciones.getTiempoPausadoAcumuladoMs(),
                    calendarioPublicaciones.tieneOrigen()
                            ? Instant.ofEpochMilli(calendarioPublicaciones.getOrigenPublicacionesMs()) : null);
        }
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

    static final class CalendarioPublicaciones {
        private long origenPublicacionesMs;
        private long fronteraProgramadaMs;
        private long indiceBloqueFisico;
        private long tiempoPausadoAcumuladoMs;
        private long inicioPausaMs = -1L;
        private long ultimoInicioPausaMs = -1L;
        private long ultimoFinPausaMs = -1L;
        private long fronterasIncumplidas;
        private int frecuenciaIntervaloProgramadoMs;

        synchronized RegistroPublicacion registrarPublicacion(
                long publicacionRealMs, long finCalculoMs, int frecuenciaSiguienteMs) {
            if (indiceBloqueFisico == 0) {
                origenPublicacionesMs = publicacionRealMs;
                fronteraProgramadaMs = publicacionRealMs;
                indiceBloqueFisico = 1;
                frecuenciaIntervaloProgramadoMs = frecuenciaSiguienteMs;
            } else {
                indiceBloqueFisico++;
            }
            long fronteraDelBloqueMs = fronteraProgramadaMs;
            long atrasoMs = Math.max(0L, publicacionRealMs - fronteraDelBloqueMs);
            long adelantoMs = Math.max(0L, fronteraDelBloqueMs - finCalculoMs);
            if (atrasoMs > 0L && indiceBloqueFisico > 1) fronterasIncumplidas++;
            fronteraProgramadaMs = fronteraDelBloqueMs + frecuenciaSiguienteMs;
            int frecuenciaUsadaMs = indiceBloqueFisico == 1
                    ? frecuenciaSiguienteMs : frecuenciaIntervaloProgramadoMs;
            frecuenciaIntervaloProgramadoMs = frecuenciaSiguienteMs;
            return new RegistroPublicacion(
                    indiceBloqueFisico, origenPublicacionesMs, fronteraDelBloqueMs, publicacionRealMs,
                    frecuenciaUsadaMs, atrasoMs, adelantoMs,
                    publicacionRealMs - fronteraDelBloqueMs, tiempoPausadoAcumuladoMs,
                    fronterasIncumplidas);
        }

        synchronized void iniciarPausa(long ahoraMs) {
            if (inicioPausaMs < 0L) {
                inicioPausaMs = ahoraMs;
                ultimoInicioPausaMs = ahoraMs;
            }
        }

        synchronized long finalizarPausa(long ahoraMs) {
            if (inicioPausaMs < 0L) return 0L;
            long duracionMs = Math.max(0L, ahoraMs - inicioPausaMs);
            tiempoPausadoAcumuladoMs += duracionMs;
            if (indiceBloqueFisico > 0) {
                origenPublicacionesMs += duracionMs;
                fronteraProgramadaMs += duracionMs;
            }
            ultimoFinPausaMs = ahoraMs;
            inicioPausaMs = -1L;
            return duracionMs;
        }

        synchronized boolean tieneOrigen() { return indiceBloqueFisico > 0; }
        synchronized long getOrigenPublicacionesMs() { return origenPublicacionesMs; }
        synchronized long getFronteraProgramadaMs() { return fronteraProgramadaMs; }
        synchronized long getIndiceBloqueFisico() { return indiceBloqueFisico; }
        synchronized long getTiempoPausadoAcumuladoMs() { return tiempoPausadoAcumuladoMs; }
        synchronized long getUltimoInicioPausaMs() { return ultimoInicioPausaMs; }
        synchronized long getUltimoFinPausaMs() { return ultimoFinPausaMs; }
        synchronized long getFronterasIncumplidas() { return fronterasIncumplidas; }
    }

    record RegistroPublicacion(
            long indiceBloqueFisico,
            long origenPublicacionesMs,
            long fronteraProgramadaMs,
            long publicacionRealMs,
            int frecuenciaIntervaloMs,
            long atrasoMs,
            long adelantoAntesEsperaMs,
            long derivaAcumuladaMs,
            long tiempoPausadoAcumuladoMs,
            long fronterasIncumplidas
    ) {
    }

    record BloquePreparado(
            long indiceFisico,
            LocalDateTime ventanaInicio,
            LocalDateTime ventanaFin,
            long versionPlan,
            Instant inicioCalculo,
            Instant finCalculo,
            Instant fronteraPublicacion,
            List<EventoBaseDTO> eventos,
            List<EnvioDTO> envios,
            SolucionRuta solucion,
            MetricasPlanificacionBloque metricas,
            long planificacionMs,
            long alistamientoMs,
            long taMs,
            long hundimientoMs,
            long taEstimadoMs,
            Set<String> clavesVuelos,
            AtomicBoolean invalidado,
            AtomicReference<String> causaInvalidacion
    ) {
        BloquePreparado {
            eventos = List.copyOf(eventos);
            envios = List.copyOf(envios);
            clavesVuelos = Set.copyOf(clavesVuelos);
        }
    }

    private static class SimulacionDetenidaException extends RuntimeException {
    }
}
