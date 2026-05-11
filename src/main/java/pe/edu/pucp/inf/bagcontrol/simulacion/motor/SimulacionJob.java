package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Getter;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.model.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.repo.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimulacionJob implements Runnable {

    private final int saMs = 20000;
    private final String simulacionId;
    @Getter
    private final LocalDate fechaInicio;
    private final Integer cantidadDias;
    @Getter
    private final int k;
    @Getter
    private final String algoritmo;

    private final PlanificadorService planificadorService;
    private final WebSocketPublisher webSocketPublisher;
    private final ConfiguracionColapsoDTO configuracionColapso;
    private final SimulacionStateMutator mutator;
    private final SimulacionEventosFactory eventosFactory;

    private final AtomicBoolean pausada = new AtomicBoolean(false);
    private final AtomicBoolean detenida = new AtomicBoolean(false);
    private final LocalDateTime fechaCreacion = LocalDateTime.now();

    @Getter
    private final SimulacionState state;
    private volatile Thread hilo;

    public SimulacionJob(
            String simulacionId, LocalDate fechaInicio, Integer cantidadDias, int k, String algoritmo,
            PlanificadorService planificadorService, AeropuertoRepository aeropuertoRepository,
            WebSocketPublisher webSocketPublisher, ConfiguracionColapsoDTO configuracionColapso
    ) {
        this.simulacionId = simulacionId;
        this.fechaInicio = fechaInicio;
        this.cantidadDias = cantidadDias;
        this.k = k;
        this.algoritmo = algoritmo;
        this.planificadorService = planificadorService;
        this.webSocketPublisher = webSocketPublisher;
        this.configuracionColapso = configuracionColapso;

        this.state = new SimulacionState(simulacionId, saMs);
        this.state.setTiempoSimuladoActual(fechaInicio.atStartOfDay().toInstant(ZoneOffset.UTC));
        this.mutator = new SimulacionStateMutator(this.state, aeropuertoRepository);
        this.eventosFactory = new SimulacionEventosFactory(configuracionColapso);
    }

    public void asignarHilo(Thread hilo) {
        this.hilo = hilo;
    }

    @Override
    public void run() {
        state.setEstado("EN_PROCESO");
        long inicioProceso = System.currentTimeMillis();
        try {
            if (configuracionColapso != null) {
                ejecutarModoColapso();
            } else {
                ejecutarModoNormal();
            }
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
            System.out.println("[PERFORMANCE] Simulación finalizada. Tiempo total de CPU: " + totalMs + "ms");
        }
    }

    private void ejecutarModoNormal() {
        publicarControl(TipoEvento.SIMULACION_INICIADA);
        verificarDetencion();

        mutator.inicializarAeropuertos();
        publicarLoteAeropuertos(state.getAeropuertosSnapshot().values().stream().toList(), "Estado inicial");

        // MEDICIÓN FASE 1: Planificación inicial
        long t1 = System.currentTimeMillis();
        SolucionRuta solucion = planificadorService.calcularSolucion(algoritmo, fechaInicio, cantidadDias);
        long tPlanificacion = System.currentTimeMillis() - t1;
        System.out.println("[PERFORMANCE] Fase 1 (Planificación Global) tomó: " + tPlanificacion + "ms");

        state.setSolucionActual(solucion);
        mutator.cargarMaletasInicialesEnOrigenes(solucion);
        mutator.indexarEnviosPorVuelo(solucion);

        publicarControl(TipoEvento.PLAN_GENERADO);
        List<SimulacionEventosFactory.EventoProgramado> lineaDeTiempo = eventosFactory.generarLineaDeTiempo(solucion);

        // FASE 2: Motor de Batches
        double minutosRealesPorIteracion = state.getVelocidadMs() / 60000.0;
        long minutosPorBatch = Math.max(1, Math.round(minutosRealesPorIteracion * k));
        long totalMinutosSimulacion = cantidadDias * 24L * 60L;
        long totalBatches = (long) Math.ceil((double) totalMinutosSimulacion / minutosPorBatch);

        LocalDateTime ventanaInicio = fechaInicio.atStartOfDay();
        int indiceEvento = 0;

        for (long batchActual = 0; batchActual < totalBatches; batchActual++) {
            long inicioBatchReal = System.currentTimeMillis();
            esperarSiPausadaODetenida();

            LocalDateTime ventanaFin = ventanaInicio.plusMinutes(minutosPorBatch);
            Instant ventanaFinUtc = ventanaFin.toInstant(ZoneOffset.UTC);
            state.setTiempoSimuladoActual(ventanaFinUtc);

            List<EventoBaseDTO> eventosDelLote = new ArrayList<>();
            while (indiceEvento < lineaDeTiempo.size()) {
                SimulacionEventosFactory.EventoProgramado eventoProgramado = lineaDeTiempo.get(indiceEvento);
                LocalDateTime horaEventoLocal = LocalDateTime.ofInstant(eventoProgramado.instantUtc(), ZoneOffset.UTC);

                if (horaEventoLocal.isAfter(ventanaFin) || horaEventoLocal.isEqual(ventanaFin)) break;

                procesarEventoProgramado(eventoProgramado, eventosDelLote);
                indiceEvento++;
            }

            publicarLote(eventosDelLote, ventanaInicio.toInstant(ZoneOffset.UTC), ventanaFinUtc);
            ventanaInicio = ventanaFin;

            long duracionBatchReal = System.currentTimeMillis() - inicioBatchReal;

            // ADVERTENCIA DE TIEMPO REAL
            if (duracionBatchReal > state.getVelocidadMs()) {
                System.err.println("[WARNING] El procesamiento del lote #" + (batchActual+1) + " tardó " + duracionBatchReal + "ms, excediendo el saMs de " + state.getVelocidadMs() + "ms. Se producirá LAG.");
            }

            dormir(state.getVelocidadMs());
        }

        state.setEstado("FINALIZADA");
        publicarControl(TipoEvento.SIMULACION_FINALIZADA);
    }

    private void ejecutarModoColapso() {
        publicarControl(TipoEvento.SIMULACION_INICIADA);
        verificarDetencion();

        mutator.inicializarAeropuertos();
        List<Envio> pendientes = new ArrayList<>();
        int ciclo = 0;
        int diaOffset = 0;

        while (!esTerminal()) {
            long inicioCicloReal = System.currentTimeMillis();
            esperarSiPausadaODetenida();
            ciclo++;

            LocalDate ventanaInicio = fechaInicio.plusDays(diaOffset);
            LocalDate ventanaFin = ventanaInicio.plusDays(1);
            state.setCicloActual(ciclo);
            state.setTiempoSimuladoActual(ventanaInicio.atStartOfDay().toInstant(ZoneOffset.UTC));

            List<Envio> nuevos = planificadorService.obtenerEnviosEnVentana(ventanaInicio.atStartOfDay(), ventanaFin.atStartOfDay());
            if (nuevos.isEmpty() && pendientes.isEmpty() && diaOffset > 0) break;

            List<Envio> enviosAProcesar = new ArrayList<>(pendientes);
            enviosAProcesar.addAll(nuevos);

            // MEDICIÓN: Replanificación iterativa
            long t1 = System.currentTimeMillis();
            SolucionRuta solucion = planificadorService.calcularSolucionParaEnvios(algoritmo, ventanaInicio, 1, enviosAProcesar);
            long tAlgoritmoCiclo = System.currentTimeMillis() - t1;
            System.out.println("[PERFORMANCE] Ciclo #" + ciclo + ": Algoritmo tardó " + tAlgoritmoCiclo + "ms");

            state.setSolucionActual(solucion);
            mutator.indexarEnviosPorVuelo(solucion);

            pendientes = solucion.getAsignaciones().stream()
                    .filter(a -> a.getItinerario() == null)
                    .map(RutaAsignada::getEnvio).toList();

            MetricasColapsoDTO metricas = eventosFactory.calcularMetricasColapso(ciclo, ventanaInicio, ventanaFin, nuevos, pendientes, enviosAProcesar, solucion, state.getAeropuertosSnapshot());
            List<String> criterios = eventosFactory.detectarCriteriosColapso(metricas);

            mutator.actualizarInventarioDesdeSolucionColapso(solucion);
            state.setMetricasColapsoActuales(metricas);

            publicarLote(List.of(new EventoCicloColapsoDTO(LocalDateTime.now().toString(), metricas)), state.getTiempoSimuladoActual(), ventanaFin.atStartOfDay().toInstant(ZoneOffset.UTC));

            if (!criterios.isEmpty()) {
                state.setEstadoColapso("COLAPSO_DETECTADO");
                state.setMotivoColapso(criterios.get(0));
                publicarLote(List.of(new EventoColapsoDTO(LocalDateTime.now().toString(), simulacionId, state.getTiempoSimuladoActual().toString(), ciclo, criterios.get(0), criterios, metricas)), state.getTiempoSimuladoActual(), state.getTiempoSimuladoActual());
                state.setEstado("FINALIZADA");
                publicarControl(TipoEvento.SIMULACION_FINALIZADA);
                return;
            }

            long duracionTotalCiclo = System.currentTimeMillis() - inicioCicloReal;
            if (duracionTotalCiclo > state.getVelocidadMs()) {
                System.err.println("[WARNING] El ciclo de colapso #" + ciclo + " tardó " + duracionTotalCiclo + "ms. ¡Es mayor que el saMs!");
            }

            esperarConControl();
            diaOffset++;
        }
        state.setEstadoColapso("NO_DETECTADO");
        state.setEstado("FINALIZADA");
        publicarControl(TipoEvento.SIMULACION_FINALIZADA);
    }

    // El resto de métodos auxiliares (procesarEventoProgramado, dormir, etc.) se mantienen igual...
    private void procesarEventoProgramado(SimulacionEventosFactory.EventoProgramado eventoProgramado, List<EventoBaseDTO> eventos) {
        SimulacionEventosFactory.VueloAgrupado vuelo = eventoProgramado.vuelo();
        if (eventoProgramado.tipo() == TipoEvento.VUELO_DESPEGA) {
            mutator.restarMaletas(vuelo.origenIata(), vuelo.cantidadMaletas());
            agregarEventoAeropuerto(eventos, vuelo.origenIata(), TipoEvento.AEROPUERTO_ACTUALIZADO);
            eventos.add(eventosFactory.crearEventoVuelo(vuelo, TipoEvento.VUELO_DESPEGA, "EN_VUELO"));
        } else if (eventoProgramado.tipo() == TipoEvento.VUELO_ATERRIZA) {
            mutator.sumarMaletas(vuelo.destinoIata(), vuelo.cantidadMaletas());
            eventos.add(eventosFactory.crearEventoVuelo(vuelo, TipoEvento.VUELO_ATERRIZA, "ATERRIZADO"));
            agregarEventoAeropuerto(eventos, vuelo.destinoIata(), TipoEvento.AEROPUERTO_ACTUALIZADO);
        }
    }

    private void agregarEventoAeropuerto(List<EventoBaseDTO> eventos, String codigoIata, TipoEvento tipoEvento) {
        Aeropuerto aeropuerto = state.getAeropuertosSnapshot().get(codigoIata);
        if (aeropuerto == null) return;
        EventoAeropuertoDTO evento = eventosFactory.crearEventoAeropuerto(aeropuerto, state.getInventarioSnapshot().getOrDefault(codigoIata, 0));
        evento.setTipo(tipoEvento);
        eventos.add(evento);
    }

    private void publicarLoteAeropuertos(List<Aeropuerto> aeropuertos, String estado) {
        List<EventoBaseDTO> eventos = new ArrayList<>();
        for (Aeropuerto aeropuerto : aeropuertos) {
            EventoAeropuertoDTO evento = eventosFactory.crearEventoAeropuerto(aeropuerto, state.getInventarioSnapshot().getOrDefault(aeropuerto.getCodigoIata(), 0));
            evento.setMensaje(estado);
            eventos.add(evento);
        }
        publicarLote(eventos, state.getTiempoSimuladoActual(), state.getTiempoSimuladoActual());
    }

    private void publicarControl(TipoEvento tipoEvento) {
        EventoBaseDTO evento = new EventoBaseDTO(tipoEvento, LocalDateTime.now().toString());
        Instant ventana = state.getTiempoSimuladoActual() != null ? state.getTiempoSimuladoActual() : Instant.now();
        publicarLote(List.of(evento), ventana, ventana);
    }

    private void publicarLote(List<EventoBaseDTO> eventos, Instant ventanaInicio, Instant ventanaFin) {
        if (eventos.isEmpty()) return;
        LoteEventosDTO lote = new LoteEventosDTO(simulacionId, state.siguienteLote(), ventanaInicio != null ? ventanaInicio.toString() : null, ventanaFin != null ? ventanaFin.toString() : null, eventos.size(), eventos);
        state.setUltimoLoteEmitido(lote);
        webSocketPublisher.publicarLote(simulacionId, lote);
    }

    private void esperarConControl() {
        long objetivo = state.getVelocidadMs();
        long acumulado = 0L;
        long paso = 100L;
        while (acumulado < objetivo) {
            esperarSiPausadaODetenida();
            long dormirMs = Math.min(paso, objetivo - acumulado);
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
            dormir(300);
        }
        verificarDetencion();
    }

    private void verificarDetencion() {
        if (detenida.get()) throw new SimulacionDetenidaException();
    }

    private boolean esTerminal() {
        return "FINALIZADA".equals(state.getEstado()) || "DETENIDA".equals(state.getEstado()) || "ERROR".equals(state.getEstado());
    }

    private void dormir(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) {
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

    public void cambiarVelocidad(long saMs) {
        state.cambiarVelocidad(saMs);
        publicarControl(TipoEvento.VELOCIDAD_CAMBIADA);
    }

    public boolean estaPausada() { return pausada.get(); }
    public boolean estaDetenida() { return detenida.get(); }
    public LocalDateTime getFechaCreacion() { return fechaCreacion; }

    private static class SimulacionDetenidaException extends RuntimeException {}
}