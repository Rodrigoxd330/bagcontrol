package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Getter;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
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
    private long tiempoUltimoLoteMs = 0L;

    private volatile Thread hilo;

    public SimulacionJob(
            String simulacionId, LocalDateTime horaInicio, LocalDateTime horaFin, int k, String algoritmo,
            PlanificadorService planificadorService, AeropuertoRepository aeropuertoRepository,
            WebSocketPublisher webSocketPublisher, SimulacionEventosFactory simulacionEventosFactory,
            SimulacionState state, ConfiguracionColapsoDTO configuracionColapsoDTO,
            SimulacionStateMutator simulacionStateMutator, String modo
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
    }

    public void asignarHilo(Thread hilo) {
        this.hilo = hilo;
    }

    @Override
    public void run() {
        state.setEstado("EN_PROCESO");
        long inicioProceso = System.currentTimeMillis();
        try {
            ejecutarSimulacion();
        } catch (SimulacionDetenidaException e) {
            state.setEstado("DETENIDA");
            publicarControl(TipoEvento.SIMULACION_DETENIDA);
        } catch (Exception e) {
            System.err.println("Error en la simulacion " + simulacionId + ": " + e.getMessage());
            e.printStackTrace();
            state.setEstado("ERROR");
            publicarControl(TipoEvento.ERROR);
        } finally {
            long totalMs = System.currentTimeMillis() - inicioProceso;
            System.out.println("[SIM5D-PERFORMANCE] simulacionId=" + simulacionId
                    + " tiempoTotalMs=" + totalMs
                    + " bloquesProcesados=" + state.getBloquesProcesados()
                    + " saltoAlgoritmoMinutos=" + k
                    + " saltoConsumoDatosMinutos=" + k);
        }
    }

    private void ejecutarSimulacion() {
        simulacionStateMutator.inicializarAeropuertos();
        state.setTiempoActual(horaInicio);
        planificadorService.precargarEnvios(horaInicio);
        publicarControl(TipoEvento.SIMULACION_INICIADA);

        LocalDateTime tiempoFin = horaFin == null ? LocalDateTime.MAX : horaFin;
        Map<String, EventoVueloDTO> eventosVueloPostergados = new java.util.LinkedHashMap<>();
        publicarConfiguracionRendimiento();

        while (state.getTiempoActual().isBefore(tiempoFin)) {
            // --- FASE 1: INICIO DE MEDICIÓN DE TA ---
            long inicioCronometroTa = System.currentTimeMillis();

            verificarDetencion();
            esperarSiPausadaODetenida();

            LocalDateTime ventanaInicio = state.getTiempoActual();
            LocalDateTime ventanaFin = ventanaInicio.plusMinutes(k); // K determina el salto simulado
            if (ventanaFin.isAfter(tiempoFin)) {ventanaFin = tiempoFin;}

            Instant ventanaFinUtc = ventanaFin.toInstant(ZoneOffset.UTC);
            int ciclo = state.getCicloActual() + 1;
            state.setCicloActual(ciclo);

            // --- FASE 2: EXTRACCIÓN DE CONTEXTO ---
            //Eventos batch es la lisa de todos los eventos que se van a enviar al front
            List<EventoBaseDTO> eventosBatch = new ArrayList<>();
            Set<String> clavesEventosPostergadosEnBatch = new HashSet<>();
            extraerEventosVueloPostergados(
                    ventanaFinUtc, eventosBatch, eventosVueloPostergados, clavesEventosPostergadosEnBatch
            );
            agregarEventosVuelosCancelados(ventanaInicio, ventanaFin, eventosBatch);

            Map<String, Integer> inventarioReservado = PlanificadorUtils.construirInventarioReservado(
                    state.getEnviosEnSeguimiento(),
                    state.getEnviosEntregados(),
                    state.getInventarioSnapshot(),
                    state.getTiempoActual().toInstant(ZoneOffset.UTC)
            );
            SolucionRuta solucion = planificadorService.calcularSolucion(
                    algoritmo, ventanaInicio, ventanaFin, state.getEnviosPendientes(), inventarioReservado
            );
            state.setSolucionActual(solucion);

            System.out.printf("[PLANIFICACION-OK] ventana=%s -> %s | envios=%d | algoritmo=%s | fitness=%.2f | planMs=%d%n",
                    ventanaInicio, ventanaFin, solucion.getAsignaciones().size(),
                    algoritmo, solucion.getFitness(), System.currentTimeMillis() - inicioCronometroTa);

            // --- FASE 4: MUTACIÓN FÍSICA E INDEXACIÓN DEL ESTADO ---
            //simulacionStateMutator.indexarEnviosPorVuelo(solucion);
            registrarEnviosNuevos(solucion, eventosBatch, inventarioReservado);

            // --- FASE 5: GENERACIÓN Y ORDENAMIENTO DE EVENTOS EN LA VENTANA ---
            SimulacionEventosFactory.ResultadoEventosVuelo eventosVuelos =
                    simulacionEventosFactory.generarEventosVuelo(solucion, ventanaFinUtc);
            eventosBatch.addAll(eventosVuelos.actuales());
            agregarEventosVueloPostergados(eventosVuelos.futuros(), eventosVueloPostergados);
            eventosBatch.sort(comparadorEventos());

            // --- FASE 6: CÁLCULO DE SLA Y COLAPSOS ---
            IncumplimientoSla incumplimiento = encontrarPrimerIncumplimientoSla(ventanaFinUtc).orElse(null);
            Instant instanteColapso = incumplimiento != null ? incumplimiento.deadline() : null;

            aplicarFisicaHasta(eventosBatch, instanteColapso, clavesEventosPostergadosEnBatch);
            marcarEnviosEntregadosHasta(
                    instanteColapso != null ? instanteColapso : ventanaFinUtc,
                    eventosBatch
            );
            agregarAlertasAeropuertosSaturados(eventosBatch);
            eventosBatch.sort(comparadorEventos());

            //Despues de aplicar fisica, guardar enviosDespachadosPorVuelo para su consulta
            simulacionStateMutator.indexarEnviosPorVuelo(enviosDespachadosPorVuelo);

            if (incumplimiento != null) {
                registrarColapsoSla(ciclo, ventanaInicio, ventanaFin, solucion, incumplimiento, eventosBatch);
            }

            consolidarEventosVuelo(eventosBatch);

            List<EnvioDTO> enviosBatch = solucion.getAsignaciones().stream().map(e -> new EnvioDTO(e.getEnvio().getIdPedido(),
                    e.getEnvio().getOrigenIata(),e.getEnvio().getDestinoIata(),e.getEnvio().getFechaHora().toString(),
                    e.getEnvio().getCantidadMaletas(),e.getEnvio().getIdCliente())).toList();

            // --- FASE 7: COLAPSO ---
            if (incumplimiento != null) {
                publicarLote(eventosBatch,enviosBatch, ventanaInicio.toInstant(ZoneOffset.UTC), ventanaFinUtc);
                state.guardarSnapshot();
                state.setBloquesProcesados(state.getBloquesProcesados() + 1);
                publicarMetricasCapacidad(solucion);
                state.setTiempoActual(LocalDateTime.ofInstant(instanteColapso, ZoneOffset.UTC));
                publicarControl(TipoEvento.SIMULACION_FINALIZADA);
                break;
            }

            actualizarPendientesParaSiguienteCiclo(solucion);

            // --- FASE 8: FIN DE TA Y COMPENSACIÓN DE TIEMPO (SA - TA) ---
            long taCalculadoMs = System.currentTimeMillis() - inicioCronometroTa;
            this.tiempoUltimoLoteMs = taCalculadoMs;

            state.setTiempoActual(ventanaFin);

            // Primer lote: publicar inmediato para arrancar el frontend. Siguientes: esperar los SA y publicar al final
            boolean esPrimerLote = state.getBloquesProcesados() == 0;
            if (!esPrimerLote && state.getTiempoActual().isBefore(tiempoFin)) {
                esperarConControl();
            }

            // --- FASE 9: ENVÍO DE DATOS A FRONTEND ---
            publicarLote(eventosBatch,enviosBatch, ventanaInicio.toInstant(ZoneOffset.UTC), ventanaFinUtc);

            System.out.printf("[LOTE-ENVIADO] numero=%d | eventos=%d | ventana=%s -> %s | taTotal=%dms | sa=%dms%n",
                    state.getUltimoLoteEmitidoNumero().get(), eventosBatch.size(),
                    ventanaInicio, ventanaFin, taCalculadoMs, saMs);

            state.guardarSnapshot();
            state.setBloquesProcesados(state.getBloquesProcesados() + 1);
            publicarMetricasCapacidad(solucion);
        }

        if (!esTerminal()) {
            state.setEstado("FINALIZADA");
            publicarControl(TipoEvento.SIMULACION_FINALIZADA);
        }
    }

    private void registrarEnviosNuevos(
            SolucionRuta solucion,
            List<EventoBaseDTO> eventosBatch,
            Map<String, Integer> inventarioReservado
    ) {
        PlanificadorUtils.reservarEscalasSolucion(solucion, inventarioReservado);
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            Envio envio = asignacion.getEnvio();
            state.getEnviosEnSeguimiento().merge(
                    envio.getIdPedido(),
                    asignacion,
                    (anterior, nueva) -> nueva.getItinerario() != null ? nueva : anterior
            );
            if (!state.getEnviosRegistrados().add(envio.getIdPedido())) {
                continue;
            }

            String origen = envio.getOrigenIata();
            state.getUltimoAeropuertoPorEnvio().put(envio.getIdPedido(), origen);
            Aeropuerto aeropuerto = state.getAeropuertosSnapshot().get(origen);
            int cantidadMaletas = envio.getCantidadMaletas();
            boolean registrado = simulacionStateMutator.sumarMaletas(origen, cantidadMaletas);
            if (!registrado) {
                System.out.println("[SIMULADOR-INVENTARIO] checkInNoRegistrado idPedido=" + envio.getIdPedido()
                        + " aeropuerto=" + origen
                        + " maletas=" + cantidadMaletas
                        + " causa=SUMAR_MALETAS_FALLO");
            }
            inventarioReservado.merge(origen, cantidadMaletas, Integer::sum);
            int inventario = state.getInventarioSnapshot().getOrDefault(origen, 0);
            eventosBatch.add(simulacionEventosFactory.crearEventoAeropuerto(
                    aeropuerto, inventario, PlanificadorUtils.obtenerFechaIngresoUtc(envio), this.state
            ));
        }
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
        List<EventoVueloDTO> eventosVuelo = eventos.stream()
                .filter(EventoVueloDTO.class::isInstance)
                .map(EventoVueloDTO.class::cast)
                .filter(evento -> evento.getTipo() != TipoEvento.VUELO_CANCELADO)
                .filter(evento -> instanteColapso == null
                        || !Instant.parse(evento.getFechaHoraEvento()).isAfter(instanteColapso))
                .sorted(Comparator.comparing(evento -> Instant.parse(evento.getFechaHoraEvento())))
                .toList();
        for (EventoVueloDTO evento : eventosVuelo) {
            aplicarFisicaVuelo(
                    evento,
                    Instant.parse(evento.getFechaHoraEvento()),
                    eventos,
                    clavesEventosPostergadosEnBatch.contains(claveEventoVuelo(evento))
            );
            marcarEnviosEntregadosHasta(Instant.parse(evento.getFechaHoraEvento()), eventos);
        }
    }

    private void aplicarFisicaVuelo(
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
        } else if (evento.getTipo() == TipoEvento.VUELO_ATERRIZA) {
            aplicarCargaRealDespachada(evento);
            String destino = evento.getDestinoIata();
            int entregadasEnDestino = registrarEntregasDirectas(evento, horaEvento);
            int maletasParaAlmacenar = evento.getCantidadMaletas() - entregadasEnDestino;
            if (maletasParaAlmacenar <= 0
                    || simulacionStateMutator.sumarMaletas(destino, maletasParaAlmacenar)) {
                agregarEventoInventario(destino, horaEvento, eventos);
            }
        }
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
        for (var vuelo : planificadorService.obtenerVuelosCanceladosEnVentana(ventanaInicio, ventanaFin)) {
            eventos.add(simulacionEventosFactory.crearEventoVueloCancelado(vuelo));
        }
    }

    private void actualizarPendientesParaSiguienteCiclo(SolucionRuta solucion) {
        state.setEnviosPendientes(solucion.obtenerEnviosConConflictos());
        if (!state.getEnviosPendientes().isEmpty()) {
            System.out.println("[SIMULADOR] enviosPendientes=" + state.getEnviosPendientes().size());
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

    private Comparator<EventoBaseDTO> comparadorEventos() {
        return Comparator.comparing(evento -> Instant.parse(evento.getFechaHoraEvento()));
    }

    private void publicarControl(TipoEvento tipoEvento) {
        Instant ventana = state.getTiempoActual() != null
                ? state.getTiempoActual().toInstant(ZoneOffset.UTC)
                : Instant.now();
        publicarLote(List.of(new EventoBaseDTO(tipoEvento, ventana.toString())), List.of(),ventana, ventana);
    }

    private void publicarConfiguracionRendimiento() {
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

    private static class SimulacionDetenidaException extends RuntimeException {
    }
}
