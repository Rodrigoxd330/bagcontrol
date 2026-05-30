package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Getter;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.DetalleColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
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
    private final AeropuertoRepository aeropuertoRepository;
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
            WebSocketPublisher webSocketPublisher, SimulacionEventosFactory simulacionEventosFactory, SimulacionState state,
            ConfiguracionColapsoDTO configuracionColapsoDTO, SimulacionStateMutator simulacionStateMutator
    ) {
        this.simulacionId = simulacionId;
        this.horaInicio = horaInicio;
        this.horaFin = horaFin;
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

        List<EventoBaseDTO> listaEventosPostergados = new ArrayList<>();

        while (state.getTiempoActual().isBefore(tiempoFin)) {
            long t0 = System.currentTimeMillis();
            List<EventoBaseDTO> listaEventosBatch = new ArrayList<>();

            verificarDetencion();
            esperarSiPausadaODetenida();

            LocalDateTime proximoTiempo = state.getTiempoActual().plusMinutes(k);
            Instant finVentanaActual = proximoTiempo.toInstant(ZoneOffset.UTC);
            int ciclo = state.getCicloActual() + 1;
            state.setCicloActual(ciclo);

            // 1. Procesar eventos de vuelos postergados que pertenecen a esta ventana física
            verificarEventosPostergados(finVentanaActual, listaEventosBatch, listaEventosPostergados);
            agregarEventosVuelosCancelados(state.getTiempoActual(), proximoTiempo, listaEventosBatch);

            // 2. Calcular Solución del Planificador
            SolucionRuta solucionActual = planificadorService.calcularSolucion(algoritmo, state.getTiempoActual(), proximoTiempo, state.getEnviosPendientes());
            state.setSolucionActual(solucionActual);
            simulacionStateMutator.indexarEnviosPorVuelo(solucionActual);

            // 3. Registrar entrada de nuevas maletas (Check-in) al iniciar la ventana
            for (RutaAsignada asignacion : solucionActual.getAsignaciones()) {
                Envio envio = asignacion.getEnvio();
                String origen = envio.getOrigenIata();

                simulacionStateMutator.sumarMaletas(origen, envio.getCantidadMaletas());

                Aeropuerto aero = state.getAeropuertosSnapshot().get(origen);
                int inv = state.getInventarioSnapshot().getOrDefault(origen, 0);
                Instant tiempoCheckIn = envio.getFechaHora().toInstant(ZoneOffset.UTC);
                listaEventosBatch.add(simulacionEventosFactory.crearEventoAeropuerto(aero, inv, tiempoCheckIn));
            }

            // Verificar si el planificador ya reportó colapso por SLA
            boolean colapsoPorSla = verificarConditionsColapso(solucionActual);

            // 4. Extraer eventos de la solución y separar los que ocurren ahora de los futuros
            SimulacionEventosFactory.ResultadoEventosVuelo eventosVuelos =
                    simulacionEventosFactory.generarEventosVuelo(solucionActual, finVentanaActual);

            listaEventosPostergados.addAll(eventosVuelos.futuros());

            // Ejecutar la física de los vuelos actuales en esta ventana
            for (EventoBaseDTO eventoActual : eventosVuelos.actuales()) {
                listaEventosBatch.add(eventoActual);
                if (eventoActual instanceof EventoVueloDTO evVuelo) {
                    Instant horaEvento = Instant.parse(evVuelo.getFechaHoraEvento());
                    aplicarFisicaVuelo(evVuelo, horaEvento, listaEventosBatch);
                }
            }

            // 5. Ordenar cronológicamente para inspeccionar la línea de tiempo real
            listaEventosBatch.sort(Comparator.comparing(e -> Instant.parse(e.getFechaHoraEvento())));

            // 6. Análisis dinámico y eficiente de Colapso por Almacén
            Instant instanteColapso = null;
            String causaColapso = null;

            if (colapsoPorSla) {
                instanteColapso = state.getTiempoActual().toInstant(ZoneOffset.UTC);
                causaColapso = state.getMotivoColapso();
            } else {
                // Evaluamos los eventos ordenados para ver EXACTAMENTE cuál rompió el límite de almacenamiento
                double umbralConfigurado = (configuracionColapsoDTO != null) ? configuracionColapsoDTO.getUmbralAeropuerto() : 1.0;

                for (EventoBaseDTO evento : listaEventosBatch) {
                    if (evento instanceof EventoAeropuertoDTO evAero) {
                        double ocupacion = (double) evAero.getMaletasActuales() / evAero.getCapacidadAlmacen();
                        if (ocupacion >= umbralConfigurado) {
                            instanteColapso = Instant.parse(evAero.getFechaHoraEvento());
                            causaColapso = "AEROPUERTO_SATURADO";
                            state.setMotivoColapso(causaColapso);
                            state.setDetalleColapso(crearDetalleColapsoAeropuerto(evAero));
                            state.setMetricasColapsoActuales(crearMetricasColapsoAeropuerto(
                                    ciclo,
                                    state.getTiempoActual(),
                                    proximoTiempo,
                                    evAero,
                                    solucionActual
                            ));
                            break; // Rompemos en el primer instante cronológico exacto
                        }
                    }
                }
            }

            // 7. Si hay colapso, cortar eventos futuros y despachar el evento definitivo de colapso
            if (causaColapso != null) {
                state.setEstado("COLAPSADA");
                Instant finalInstanteColapso = instanteColapso;

                // Remover eventos que teóricamente pasaban después del segundo exacto del colapso
                listaEventosBatch.removeIf(evento -> Instant.parse(evento.getFechaHoraEvento()).isAfter(finalInstanteColapso));

                // Agregar el DTO informativo de colapso al final del lote reducido
                listaEventosBatch.add(new EventoColapsoDTO(
                        finalInstanteColapso.toString(),
                        simulacionId,
                        finalInstanteColapso.toString(),
                        ciclo,
                        causaColapso,
                        List.of(causaColapso),
                        state.getMetricasColapsoActuales(),
                        state.getDetalleColapso()
                ));
                System.out.println("SIMULACION COLAPSADA POR: " + causaColapso + " EN " + finalInstanteColapso);
            }

            // 8. Publicar lote al Frontend
            publicarLote(listaEventosBatch, state.getTiempoActual().toInstant(ZoneOffset.UTC), finVentanaActual);
            if (causaColapso != null) {
                publicarControl(TipoEvento.SIMULACION_FINALIZADA);
                break;
            }

            // 9. Actualizar pendientes para el siguiente ciclo
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

        if (!esTerminal()) {
            state.setEstado("FINALIZADA");
            publicarControl(TipoEvento.SIMULACION_FINALIZADA);
        }
    }

    // =========================================================================================
    // Metodos auxiliares de logica de eventos y fisica
    // =========================================================================================

    private void verificarEventosPostergados(Instant finVentanaActual, List<EventoBaseDTO> listaEventosBatch, List<EventoBaseDTO> listaEventosPostergados) {
        Iterator<EventoBaseDTO> it = listaEventosPostergados.iterator();
        while (it.hasNext()) {
            EventoBaseDTO evento = it.next();
            Instant horaEvento = Instant.parse(evento.getFechaHoraEvento());

            if (!horaEvento.isAfter(finVentanaActual)) {
                it.remove();
                listaEventosBatch.add(evento);
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

    private void agregarEventosVuelosCancelados(LocalDateTime inicio, LocalDateTime fin, List<EventoBaseDTO> listaEventosBatch) {
        for (var vuelo : planificadorService.obtenerVuelosCanceladosEnVentana(inicio, fin)) {
            listaEventosBatch.add(simulacionEventosFactory.crearEventoVueloCancelado(vuelo));
        }
    }

    private void actualizarPendientesParaSiguienteCiclo(SolucionRuta solucion) {
        state.setEnviosPendientes(solucion.obtenerEnviosConConflictos());
        if (!state.getEnviosPendientes().isEmpty()) {
            System.out.println("[SIMULADOR] Arrastrando " + state.getEnviosPendientes().size() + " envíos pendientes al siguiente ciclo.");
        }
    }

    private boolean verificarConditionsColapso(SolucionRuta solucion) {
        if (solucion.getVuelosCanceladosUsadosCount() > 0) {
            solucion.getAsignaciones().stream()
                    .filter(a -> a.getItinerario() != null && a.getItinerario().contieneVueloCancelado())
                    .findFirst()
                    .ifPresent(a -> state.setDetalleColapso(crearDetalleColapso(a, "VUELO_CANCELADO")));
            state.setMotivoColapso("VUELO_CANCELADO");
            return true;
        }

        if (solucion.getExcedeSlaCount() > 0) {
            solucion.getAsignaciones().stream()
                    .filter(RutaAsignada::isExcedeSla)
                    .findFirst()
                    .ifPresent(a -> state.setDetalleColapso(crearDetalleColapso(a, "SLA_INCUMPLIDO")));
            state.setMotivoColapso("SLA_INCUMPLIDO");
            return true;
        }

        for (Envio pendiente : solucion.obtenerEnviosConConflictos()) {
            if (excedeTiempoEsperaEnAeropuerto(pendiente)) {
                state.setDetalleColapso(crearDetalleColapso(pendiente, "SIN_ITINERARIO"));
                state.setMotivoColapso("SIN_ITINERARIO");
                return true;
            }
        }
        return false;
    }

    private MetricasColapsoDTO crearMetricasColapsoAeropuerto(
            int ciclo,
            LocalDateTime ventanaInicio,
            LocalDateTime ventanaFin,
            EventoAeropuertoDTO eventoAeropuerto,
            SolucionRuta solucion
    ) {
        double ocupacion = eventoAeropuerto.getCapacidadAlmacen() > 0
                ? eventoAeropuerto.getMaletasActuales() / (double) eventoAeropuerto.getCapacidadAlmacen()
                : 0.0;

        MetricasColapsoDTO metricas = new MetricasColapsoDTO();
        metricas.setCiclo(ciclo);
        metricas.setVentanaInicio(ventanaInicio.toString());
        metricas.setVentanaFin(ventanaFin.toString());
        metricas.setAeropuertosSaturados(1);
        metricas.setOcupacionAeropuertoMaxima(ocupacion);
        metricas.setFitnessUltimaSolucion(solucion != null ? solucion.getFitness() : 0.0);
        metricas.setMotivoColapso("AEROPUERTO_SATURADO");
        metricas.setCodigoAeropuertoColapsado(eventoAeropuerto.getCodigoAeropuerto());
        metricas.setMaletasActualesAeropuerto(eventoAeropuerto.getMaletasActuales());
        metricas.setCapacidadAeropuerto(eventoAeropuerto.getCapacidadAlmacen());
        metricas.setPorcentajeOcupacionAeropuerto(eventoAeropuerto.getPorcentajeOcupacion());
        metricas.setCausaPrincipal("AEROPUERTO_SATURADO");
        return metricas;
    }

    private DetalleColapsoDTO crearDetalleColapsoAeropuerto(EventoAeropuertoDTO eventoAeropuerto) {
        DetalleColapsoDTO detalle = new DetalleColapsoDTO();
        detalle.setOrigenIata(eventoAeropuerto.getCodigoAeropuerto());
        detalle.setCantidadMaletas(eventoAeropuerto.getMaletasActuales());
        detalle.setMotivo("AEROPUERTO_SATURADO");
        detalle.setHoraSimulada(eventoAeropuerto.getFechaHoraEvento());
        detalle.setTipo("AEROPUERTO_SATURADO");
        detalle.setCodigoAeropuerto(eventoAeropuerto.getCodigoAeropuerto());
        detalle.setCapacidad(eventoAeropuerto.getCapacidadAlmacen());
        detalle.setMaletasActuales(eventoAeropuerto.getMaletasActuales());
        detalle.setPorcentajeOcupacion(eventoAeropuerto.getPorcentajeOcupacion());
        return detalle;
    }

    private DetalleColapsoDTO crearDetalleColapso(RutaAsignada asignacion, String motivo) {
        Envio envio = asignacion.getEnvio();
        Long vueloAfectado = null;
        String itinerarioAfectado = null;
        if (asignacion.getItinerario() != null) {
            itinerarioAfectado = asignacion.getItinerario().getIdItinerario();
            vueloAfectado = asignacion.getItinerario().getVuelos().stream()
                    .filter(VueloInstanciado::isEstaCancelado)
                    .map(VueloInstanciado::getCodigoBase)
                    .findFirst()
                    .orElseGet(() -> asignacion.getItinerario().getVuelos().isEmpty()
                            ? null
                            : asignacion.getItinerario().getVuelos().get(0).getCodigoBase());
        }
        return new DetalleColapsoDTO(
                envio.getIdPedido(),
                envio.getOrigenIata(),
                envio.getDestinoIata(),
                envio.getCantidadMaletas(),
                motivo,
                vueloAfectado,
                itinerarioAfectado,
                state.getTiempoActual().toInstant(ZoneOffset.UTC).toString()
        );
    }

    private DetalleColapsoDTO crearDetalleColapso(Envio envio, String motivo) {
        return new DetalleColapsoDTO(
                envio.getIdPedido(),
                envio.getOrigenIata(),
                envio.getDestinoIata(),
                envio.getCantidadMaletas(),
                motivo,
                null,
                null,
                state.getTiempoActual().toInstant(ZoneOffset.UTC).toString()
        );
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

    private void publicarControl(TipoEvento tipoEvento) {
        Instant ventana = state.getTiempoActual() != null ? state.getTiempoActual().toInstant(ZoneOffset.UTC) : Instant.now();
        EventoBaseDTO evento = new EventoBaseDTO(tipoEvento, ventana.toString());
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

    public boolean estaPausada() { return pausada.get(); }
    public boolean estaDetenida() { return detenida.get(); }
    public LocalDateTime getFechaInicio() { return horaInicio; }

    private static class SimulacionDetenidaException extends RuntimeException {}
}
