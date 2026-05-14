package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Getter;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.model.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.repo.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoBaseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.TipoEvento;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimulacionJob implements Runnable {

    @Getter
    private final int saMs = 10_000;

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

        while (state.getTiempoActual().isBefore(tiempoFin)) {
            long t0 = System.currentTimeMillis();

            verificarDetencion();
            esperarSiPausadaODetenida();
            LocalDateTime proximoTiempo = state.getTiempoActual().plusMinutes(k);
            SolucionRuta solucionActual = planificadorService.calcularSolucion(
                    algoritmo,
                    state.getTiempoActual(),
                    proximoTiempo,
                    state.getEnviosPendientes()
            );
            boolean colapso = verificarCondicionesColapso(solucionActual);

            // 3. Extraer eventos de la solución
            List<SimulacionEventosFactory.EventoProgramado> eventosOrdenados = simulacionEventosFactory.generarLineaDeTiempo(solucionActual);
            List<EventoBaseDTO> loteEventos = generarLoteDeEventos(eventosOrdenados);

            if (colapso) {
                EventoColapsoDTO eventoColapsoDTO = new EventoColapsoDTO();
                state.setEstado("COLAPSADA");
                loteEventos.add(eventoColapsoDTO);
            }
            publicarLote(loteEventos, state.getTiempoActual().toInstant(ZoneOffset.UTC), proximoTiempo.toInstant(ZoneOffset.UTC));
            if(colapso) break;

            // 6. Preparar datos para el siguiente ciclo K
            actualizarPendientesParaSiguienteCiclo(solucionActual);
            state.setTiempoActual(proximoTiempo);

            long tLote = System.currentTimeMillis() - t0;
            System.out.printf("[SIMULADOR] Lote #%d publicado | eventos=%d | ventana=%s→%s | tiempoEjecucion=%d%n",
                    state.getUltimoLoteEmitidoNumero().get(),
                    loteEventos.size(),
                    state.getTiempoActual(),
                    proximoTiempo,
                    tLote
            );

            esperarConControl();
        }

        if (!"COLAPSADA".equals(state.getEstado()) && !esTerminal()) {
            state.setEstado("FINALIZADA");
            publicarControl(TipoEvento.SIMULACION_FINALIZADA);
        }
    }

    // =========================================================================================
    // MÉTODOS DE PUBLICACIÓN Y COMUNICACIÓN (WEBSOCKETS)
    // =========================================================================================

    /*
     * Entendemos control como un evento que corresponde a un instante, no a los eventos de simulacion
     * Es decir, aquí entra -> pausas, detenciones, incios, fines, etc.
     */
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

    /*
    * Para poder esperar la parte de detener/reanudar entre ejecuciones
    * se verifica cada 'paso' ms.
    */
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

    // =========================================================================================
    // FUNCIONES PENDIENTES POR IMPLEMENTAR (STUBS)
    // =========================================================================================


    private boolean verificarCondicionesColapso(SolucionRuta solucion) {
        // 1. Colapso porque el planificador asignó un itinerario que llega muy tarde (SLA roto en vuelo)
        if (solucion.getExcedeSlaCount() > 0) {
            state.setMotivoColapso("SLA_INCUMPLIDO_PLANIFICADO");
            return true;
        }

        // 2. Colapso porque hay maletas sin vuelo que ya llevan demasiado tiempo esperando (SLA roto en tierra)
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

        // Comparamos la hora en que el cliente dejó la maleta contra el reloj actual de la simulación
        long horasEsperando = java.time.Duration.between(
                envio.getFechaHora(),
                state.getTiempoActual()
        ).toHours();

        return horasEsperando > horasLimite;
    }

    private List<EventoBaseDTO> generarLoteDeEventos(List<SimulacionEventosFactory.EventoProgramado> eventosOrdenados) {
        List<EventoBaseDTO> loteEventos = new ArrayList<>();

        for (SimulacionEventosFactory.EventoProgramado eventoProgramado : eventosOrdenados) {
            SimulacionEventosFactory.VueloAgrupado vuelo = eventoProgramado.vuelo();
            String origenIata = vuelo.origenIata();
            String destinoIata = vuelo.destinoIata();

            if (eventoProgramado.tipo() == TipoEvento.VUELO_DESPEGA) {
                // 1. Mutar la memoria física
                simulacionStateMutator.restarMaletas(origenIata, vuelo.cantidadMaletas());

                // 2. Avisar al front del nuevo inventario del aeropuerto
                Aeropuerto aeropuertoOrigen = state.getAeropuertosSnapshot().get(origenIata);
                int inventarioOrigen = state.getInventarioSnapshot().getOrDefault(origenIata, 0);
                loteEventos.add(simulacionEventosFactory.crearEventoAeropuerto(aeropuertoOrigen, inventarioOrigen));

                // 3. Avisar al front del despegue
                loteEventos.add(simulacionEventosFactory.crearEventoVuelo(vuelo, TipoEvento.VUELO_DESPEGA, "EN_VUELO"));

            } else if (eventoProgramado.tipo() == TipoEvento.VUELO_ATERRIZA) {
                // 1. Mutar la memoria física
                simulacionStateMutator.sumarMaletas(destinoIata, vuelo.cantidadMaletas());

                // 2. Avisar al front del nuevo inventario del aeropuerto
                Aeropuerto aeropuertoDestino = state.getAeropuertosSnapshot().get(destinoIata);
                int inventarioDestino = state.getInventarioSnapshot().getOrDefault(destinoIata, 0);
                loteEventos.add(simulacionEventosFactory.crearEventoAeropuerto(aeropuertoDestino, inventarioDestino));

                // 3. Avisar al front del aterrizaje
                loteEventos.add(simulacionEventosFactory.crearEventoVuelo(vuelo, TipoEvento.VUELO_ATERRIZA, "ATERRIZADO"));
            }
        }
        return loteEventos;
    }

    private void actualizarPendientesParaSiguienteCiclo(SolucionRuta solucion) {
        state.setEnviosPendientes(solucion.obtenerEnviosConConflictos());
        if (!state.getEnviosPendientes().isEmpty()) {
            System.out.println("[SIMULADOR] Arrastrando " + state.getEnviosPendientes().size() + " envíos pendientes al siguiente ciclo.");
        }
    }
}