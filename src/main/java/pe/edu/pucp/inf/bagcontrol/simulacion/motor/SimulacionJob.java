package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Getter;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.model.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.repo.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimulacionJob implements Runnable {

    @Getter
    private final int saMs = 30_000;

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
    private final AeropuertoRepository aeropuertoRepository;
    private final WebSocketPublisher webSocketPublisher;
    private final ConfiguracionColapsoDTO configuracionColapsoDTO;
    private final SimulacionEventosFactory simulacionEventosFactory;
    @Getter
    private final SimulacionState state;
    private final SimulacionStateMutator simulacionStateMutator;

    private volatile Thread hilo;

    public SimulacionJob(
            String simulacionId, LocalDate horaInicio, LocalDate horaFin, int k, String algoritmo,
            PlanificadorService planificadorService, AeropuertoRepository aeropuertoRepository,
            WebSocketPublisher webSocketPublisher, SimulacionEventosFactory simulacionEventosFactory, SimulacionState state,
            ConfiguracionColapsoDTO configuracionColapsoDTO, SimulacionStateMutator simulacionStateMutator
    ) {
        this.simulacionId = simulacionId;
        this.horaInicio = horaInicio.atStartOfDay();
        this.horaFin = (horaFin != null) ? horaFin.atStartOfDay() : null;
        this.k = k;
        this.algoritmo = algoritmo;

        //Dependencias
        this.planificadorService = planificadorService;
        this.aeropuertoRepository = aeropuertoRepository;
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
            System.out.println("[PERFORMANCE] Simulacion finalizada. Tiempo total de CPU: " + totalMs + "ms");
        }
    }

    private void ejecutarSimulacion() {
        simulacionStateMutator.inicializarAeropuertos();
        publicarControl(TipoEvento.SIMULACION_INICIADA);
        state.setTiempoActual(horaInicio);
        LocalDateTime tiempoFin = (horaFin == null) ? LocalDateTime.MAX : horaFin;

        // La lista global que perdura entre ciclos
        List<EventoBaseDTO> listaEventosPostergados = new ArrayList<>();

        while (state.getTiempoActual().isBefore(tiempoFin)) {
            long t0 = System.currentTimeMillis();
            List<EventoBaseDTO> listaEventosBatch = new ArrayList<>(); // Lo que se envía al Front en este ciclo

            verificarDetencion();
            esperarSiPausadaODetenida();

            LocalDateTime proximoTiempo = state.getTiempoActual().plusMinutes(k);
            Instant finVentanaActual = proximoTiempo.toInstant(ZoneOffset.UTC);

            // 1. Ver si los eventos (vuelos) postergados se van a procesar en esta iteración (se agregan a listaBatch)
            verificarEventosPostergados(finVentanaActual, listaEventosBatch, listaEventosPostergados);

            // 2. Calcular Solución
            SolucionRuta solucionActual = planificadorService.calcularSolucion(algoritmo, state.getTiempoActual(), proximoTiempo, state.getEnviosPendientes());

            // 3. Se registran las nuevas maletas
            for (RutaAsignada asignacion : solucionActual.getAsignaciones()) {
                Envio envio = asignacion.getEnvio();
                String origen = envio.getOrigenIata();

                simulacionStateMutator.sumarMaletas(origen, envio.getCantidadMaletas());

                Aeropuerto aero = state.getAeropuertosSnapshot().get(origen);
                int inv = state.getInventarioSnapshot().getOrDefault(origen, 0);
                Instant tiempoCheckIn = envio.getFechaHora().toInstant(ZoneOffset.UTC);
                listaEventosBatch.add(simulacionEventosFactory.crearEventoAeropuerto(aero, inv, tiempoCheckIn));
            }
            boolean colapso = verificarCondicionesColapso(solucionActual);

            // 4. Extraer eventos de la solución y separar Actuales de Futuros
            SimulacionEventosFactory.ResultadoEventosVuelo eventosVuelos =
                    simulacionEventosFactory.generarEventosVuelo(solucionActual, finVentanaActual);

            // Los futuros se van a la refrigeradora global
            listaEventosPostergados.addAll(eventosVuelos.futuros());

            // Los actuales entran al Batch y generan los batch de actualización de aeropuertos (la resta)
            for (EventoBaseDTO eventoActual : eventosVuelos.actuales()) {
                listaEventosBatch.add(eventoActual);
                if (eventoActual instanceof EventoVueloDTO evVuelo) {
                    Instant horaEvento = Instant.parse(evVuelo.getFechaHoraEvento());
                    aplicarFisicaVuelo(evVuelo, horaEvento, listaEventosBatch);
                }
            }
            // 5. Ordenar el Lote cronológicamente
            listaEventosBatch.sort(Comparator.comparing(e -> Instant.parse(e.getFechaHoraEvento())));

            // 6. Manejo de Colapso y Envío
            //TODO (Faltaría identificar el momento exacto en donde se colapsó, DEBERÍA ESTAR JUSTO DEPUÉS DEL EVENTO QUE HIZO COLAPSAR)
            if (colapso) {
                state.setEstado("COLAPSADA");
                listaEventosBatch.add(new EventoColapsoDTO());
                System.out.println("SIMULACION COLAPSADA");
            }
            //7 Se publica el nuevo lote
            publicarLote(listaEventosBatch, state.getTiempoActual().toInstant(ZoneOffset.UTC), finVentanaActual);
            if(colapso) break;

            // 8. Se actualizan los pendientes para el siguiente coclo
            actualizarPendientesParaSiguienteCiclo(solucionActual);

            long tLote = System.currentTimeMillis() - t0;
            System.out.printf("[SIMULADOR] Lote #%d publicado | eventos=%d | ventana=%s→%s | tiempoEjecucion=%dms%n",
                    state.getUltimoLoteEmitidoNumero().get(),
                    listaEventosBatch.size(),
                    state.getTiempoActual(),
                    proximoTiempo,
                    tLote
            );

            state.setTiempoActual(proximoTiempo);
            esperarConControl();
        }

        if (!"COLAPSADA".equals(state.getEstado()) && !esTerminal()) {
            state.setEstado("FINALIZADA");
            publicarControl(TipoEvento.SIMULACION_FINALIZADA);
        }
    }

    // =========================================================================================
    // MÉTODOS DE LÓGICA DE EVENTOS Y FÍSICA
    // =========================================================================================

    private void verificarEventosPostergados(Instant finVentanaActual, List<EventoBaseDTO> listaEventosBatch, List<EventoBaseDTO> listaEventosPostergados) {
        Iterator<EventoBaseDTO> it = listaEventosPostergados.iterator();

        while (it.hasNext()) {
            EventoBaseDTO evento = it.next();
            Instant horaEvento = Instant.parse(evento.getFechaHoraEvento());

            if (!horaEvento.isAfter(finVentanaActual)) {
                it.remove(); // Sale de la sala de espera
                listaEventosBatch.add(evento); // Entra al envío actual

                if (evento instanceof EventoVueloDTO evVuelo) {
                    aplicarFisicaVuelo(evVuelo, horaEvento, listaEventosBatch);
                }
            }
        }
    }

    private void aplicarFisicaVuelo(EventoVueloDTO evVuelo, Instant horaEvento, List<EventoBaseDTO> listaEventosBatch) {
        if (evVuelo.getTipo() == TipoEvento.VUELO_DESPEGA) {
            String origen = evVuelo.getOrigenIata();
            simulacionStateMutator.restarMaletas(origen, evVuelo.getCantidadMaletas());

            Aeropuerto aero = state.getAeropuertosSnapshot().get(origen);
            int inv = state.getInventarioSnapshot().getOrDefault(origen, 0);
            listaEventosBatch.add(simulacionEventosFactory.crearEventoAeropuerto(aero, inv, horaEvento));

        } else if (evVuelo.getTipo() == TipoEvento.VUELO_ATERRIZA) {
            String destino = evVuelo.getDestinoIata();
            simulacionStateMutator.sumarMaletas(destino, evVuelo.getCantidadMaletas());

            Aeropuerto aero = state.getAeropuertosSnapshot().get(destino);
            int inv = state.getInventarioSnapshot().getOrDefault(destino, 0);
            listaEventosBatch.add(simulacionEventosFactory.crearEventoAeropuerto(aero, inv, horaEvento));
        }
    }

    private void actualizarPendientesParaSiguienteCiclo(SolucionRuta solucion) {
        state.setEnviosPendientes(solucion.obtenerEnviosConConflictos());
        if (!state.getEnviosPendientes().isEmpty()) {
            System.out.println("[SIMULADOR] Arrastrando " + state.getEnviosPendientes().size() + " envíos pendientes al siguiente ciclo.");
        }
    }

    private boolean verificarCondicionesColapso(SolucionRuta solucion) {
        if (solucion.getExcedeSlaCount() > 0) {
            state.setMotivoColapso("SLA_INCUMPLIDO_PLANIFICADO");
            return true;
        }

        for (Envio pendiente : solucion.obtenerEnviosConConflictos()) {
            if (excedeTiempoEsperaEnAeropuerto(pendiente)) {
                state.setMotivoColapso("SLA_INCUMPLIDO_TIEMPO_DE_ESPERA");
                return true;
            }
        }
        return false;
    }

    private boolean excedeTiempoEsperaEnAeropuerto(Envio envio) {
        Aeropuerto origen = state.getAeropuertosSnapshot().get(envio.getOrigenIata());
        Aeropuerto destino = state.getAeropuertosSnapshot().get(envio.getDestinoIata());

        if (origen == null || destino == null) return false;

        boolean mismoContinente = origen.getContinente().equalsIgnoreCase(destino.getContinente());
        double horasLimite = mismoContinente ? 24.0 : 48.0;

        long horasEsperando = java.time.Duration.between(
                envio.getFechaHora(),
                state.getTiempoActual()
        ).toHours();

        return horasEsperando > horasLimite;
    }

    // =========================================================================================
    // MÉTODOS DE PUBLICACIÓN Y COMUNICACIÓN (WEBSOCKETS)
    // =========================================================================================

    private void publicarControl(TipoEvento tipoEvento) {
        EventoBaseDTO evento = new EventoBaseDTO(tipoEvento, LocalDateTime.now().toString());
        Instant ventana = state.getTiempoActual() != null ? state.getTiempoActual().toInstant(ZoneOffset.UTC) : Instant.now();
        publicarLote(List.of(evento), ventana, ventana);
    }

    private void publicarLote(List<EventoBaseDTO> eventos, Instant ventanaInicio, Instant ventanaFin) {
        if (eventos.isEmpty()) return;
        LoteEventosDTO lote = new LoteEventosDTO(
                simulacionId, state.siguienteLote(),
                ventanaInicio != null ? ventanaInicio.toString() : null,
                ventanaFin != null ? ventanaFin.toString() : null,
                eventos.size(), eventos
        );
        state.setUltimoLoteEmitido(lote);
        webSocketPublisher.publicarLote(simulacionId, lote);
    }

    // =========================================================================================
    // MÉTODOS DE CONTROL DEL HILO (PAUSA, REANUDAR, DETENER, VELOCIDAD)
    // =========================================================================================

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
                || "ERROR".equals(state.getEstado());
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

    public LocalDate getFechaInicio() {
        return horaInicio.toLocalDate();
    }

    private static class SimulacionDetenidaException extends RuntimeException {}
}