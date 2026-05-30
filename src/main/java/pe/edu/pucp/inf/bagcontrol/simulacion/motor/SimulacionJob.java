package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Getter;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
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
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.TipoEvento;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimulacionJob implements Runnable {

    @Getter
    private final int saMs = 12_000;

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

    private volatile Thread hilo;

    public SimulacionJob(
            String simulacionId, LocalDateTime horaInicio, LocalDateTime horaFin, int k, String algoritmo,
            PlanificadorService planificadorService, AeropuertoRepository aeropuertoRepository,
            WebSocketPublisher webSocketPublisher, SimulacionEventosFactory simulacionEventosFactory,
            SimulacionState state, ConfiguracionColapsoDTO configuracionColapsoDTO,
            SimulacionStateMutator simulacionStateMutator
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
        publicarControl(TipoEvento.SIMULACION_INICIADA);
        LocalDateTime tiempoFin = horaFin == null ? LocalDateTime.MAX : horaFin;
        List<EventoBaseDTO> eventosPostergados = new ArrayList<>();
        publicarConfiguracionRendimiento();

        while (state.getTiempoActual().isBefore(tiempoFin)) {
            long inicioLote = System.currentTimeMillis();
            verificarDetencion();
            esperarSiPausadaODetenida();

            LocalDateTime ventanaInicio = state.getTiempoActual();
            LocalDateTime ventanaFin = ventanaInicio.plusMinutes(k);
            if (ventanaFin.isAfter(tiempoFin)) {
                ventanaFin = tiempoFin;
            }
            Instant ventanaFinUtc = ventanaFin.toInstant(ZoneOffset.UTC);
            int ciclo = state.getCicloActual() + 1;
            state.setCicloActual(ciclo);

            List<EventoBaseDTO> eventosBatch = new ArrayList<>();
            extraerEventosPostergados(ventanaFinUtc, eventosBatch, eventosPostergados);
            agregarEventosVuelosCancelados(ventanaInicio, ventanaFin, eventosBatch);

            SolucionRuta solucion = planificadorService.calcularSolucion(
                    algoritmo, ventanaInicio, ventanaFin, state.getEnviosPendientes()
            );
            state.setSolucionActual(solucion);
            simulacionStateMutator.indexarEnviosPorVuelo(solucion);
            registrarEnviosNuevos(solucion, eventosBatch);

            SimulacionEventosFactory.ResultadoEventosVuelo eventosVuelos =
                    simulacionEventosFactory.generarEventosVuelo(solucion, ventanaFinUtc);
            eventosBatch.addAll(eventosVuelos.actuales());
            eventosPostergados.addAll(eventosVuelos.futuros());
            eventosBatch.sort(comparadorEventos());

            IncumplimientoSla incumplimiento = encontrarPrimerIncumplimientoSla(ventanaFinUtc).orElse(null);
            Instant instanteColapso = incumplimiento != null ? incumplimiento.deadline() : null;

            aplicarFisicaHasta(eventosBatch, instanteColapso);
            marcarEnviosEntregadosHasta(instanteColapso != null ? instanteColapso : ventanaFinUtc);
            agregarAlertasAeropuertosSaturados(eventosBatch);
            eventosBatch.sort(comparadorEventos());

            if (incumplimiento != null) {
                registrarColapsoSla(ciclo, ventanaInicio, ventanaFin, solucion, incumplimiento, eventosBatch);
            }

            publicarLote(eventosBatch, ventanaInicio.toInstant(ZoneOffset.UTC), ventanaFinUtc);
            state.setBloquesProcesados(state.getBloquesProcesados() + 1);

            if (incumplimiento != null) {
                state.setTiempoActual(LocalDateTime.ofInstant(instanteColapso, ZoneOffset.UTC));
                publicarControl(TipoEvento.SIMULACION_FINALIZADA);
                break;
            }

            actualizarPendientesParaSiguienteCiclo(solucion);
            long tiempoLote = System.currentTimeMillis() - inicioLote;
            System.out.printf(
                    "[SIMULADOR] lote=%d eventos=%d ventana=%s->%s tiempoEjecucionMs=%d%n",
                    state.getUltimoLoteEmitidoNumero().get(), eventosBatch.size(), ventanaInicio, ventanaFin, tiempoLote
            );
            state.setTiempoActual(ventanaFin);
            if (state.getTiempoActual().isBefore(tiempoFin)) {
                esperarConControl();
            }
        }

        if (!esTerminal()) {
            state.setEstado("FINALIZADA");
            publicarControl(TipoEvento.SIMULACION_FINALIZADA);
        }
    }

    private void registrarEnviosNuevos(SolucionRuta solucion, List<EventoBaseDTO> eventosBatch) {
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
            simulacionStateMutator.sumarMaletas(origen, envio.getCantidadMaletas());
            Aeropuerto aeropuerto = state.getAeropuertosSnapshot().get(origen);
            int inventario = state.getInventarioSnapshot().getOrDefault(origen, 0);
            eventosBatch.add(simulacionEventosFactory.crearEventoAeropuerto(
                    aeropuerto, inventario, PlanificadorUtils.obtenerFechaIngresoUtc(envio)
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

    private void extraerEventosPostergados(
            Instant ventanaFinUtc,
            List<EventoBaseDTO> eventosBatch,
            List<EventoBaseDTO> eventosPostergados
    ) {
        Iterator<EventoBaseDTO> iterator = eventosPostergados.iterator();
        while (iterator.hasNext()) {
            EventoBaseDTO evento = iterator.next();
            if (!Instant.parse(evento.getFechaHoraEvento()).isAfter(ventanaFinUtc)) {
                iterator.remove();
                eventosBatch.add(evento);
            }
        }
    }

    private void aplicarFisicaHasta(List<EventoBaseDTO> eventos, Instant instanteColapso) {
        List<EventoVueloDTO> eventosVuelo = eventos.stream()
                .filter(EventoVueloDTO.class::isInstance)
                .map(EventoVueloDTO.class::cast)
                .filter(evento -> evento.getTipo() != TipoEvento.VUELO_CANCELADO)
                .filter(evento -> instanteColapso == null
                        || !Instant.parse(evento.getFechaHoraEvento()).isAfter(instanteColapso))
                .sorted(Comparator.comparing(evento -> Instant.parse(evento.getFechaHoraEvento())))
                .toList();
        for (EventoVueloDTO evento : eventosVuelo) {
            aplicarFisicaVuelo(evento, Instant.parse(evento.getFechaHoraEvento()), eventos);
        }
    }

    private void aplicarFisicaVuelo(EventoVueloDTO evento, Instant horaEvento, List<EventoBaseDTO> eventos) {
        if (evento.getTipo() == TipoEvento.VUELO_DESPEGA) {
            String origen = evento.getOrigenIata();
            simulacionStateMutator.restarMaletas(origen, evento.getCantidadMaletas());
            agregarEventoInventario(origen, horaEvento, eventos);
        } else if (evento.getTipo() == TipoEvento.VUELO_ATERRIZA) {
            String destino = evento.getDestinoIata();
            simulacionStateMutator.sumarMaletas(destino, evento.getCantidadMaletas());
            agregarEventoInventario(destino, horaEvento, eventos);
        }
    }

    private void agregarEventoInventario(String codigoIata, Instant horaEvento, List<EventoBaseDTO> eventos) {
        Aeropuerto aeropuerto = state.getAeropuertosSnapshot().get(codigoIata);
        int inventario = state.getInventarioSnapshot().getOrDefault(codigoIata, 0);
        eventos.add(simulacionEventosFactory.crearEventoAeropuerto(aeropuerto, inventario, horaEvento));
    }

    private void marcarEnviosEntregadosHasta(Instant limite) {
        for (RutaAsignada asignacion : state.getEnviosEnSeguimiento().values()) {
            if (asignacion.getItinerario() == null) continue;
            if (!asignacion.getItinerario().getFechaHoraLlegadaUtc().isAfter(limite)) {
                String idPedido = asignacion.getEnvio().getIdPedido();
                state.getEnviosEntregados().add(idPedido);
                state.getUltimoAeropuertoPorEnvio().put(idPedido, asignacion.getEnvio().getDestinoIata());
            }
        }
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

    private Comparator<EventoBaseDTO> comparadorEventos() {
        return Comparator.comparing(evento -> Instant.parse(evento.getFechaHoraEvento()));
    }

    private void publicarControl(TipoEvento tipoEvento) {
        Instant ventana = state.getTiempoActual() != null
                ? state.getTiempoActual().toInstant(ZoneOffset.UTC)
                : Instant.now();
        publicarLote(List.of(new EventoBaseDTO(tipoEvento, ventana.toString())), ventana, ventana);
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

    private void publicarLote(List<EventoBaseDTO> eventos, Instant ventanaInicio, Instant ventanaFin) {
        if (eventos.isEmpty()) return;
        LoteEventosDTO lote = new LoteEventosDTO(
                simulacionId,
                state.siguienteLote(),
                ventanaInicio != null ? ventanaInicio.toString() : null,
                ventanaFin != null ? ventanaFin.toString() : null,
                eventos.size(),
                eventos
        );
        state.setUltimoLoteEmitido(lote);
        webSocketPublisher.publicarLote(simulacionId, lote);
    }

    private void esperarConControl() {
        long acumulado = 0L;
        long paso = 200L;
        while (acumulado < saMs) {
            esperarSiPausadaODetenida();
            long dormirMs = Math.min(paso, saMs - acumulado);
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
