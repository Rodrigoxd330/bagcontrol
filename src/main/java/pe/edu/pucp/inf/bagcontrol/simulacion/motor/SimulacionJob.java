package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.Getter;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EventoAeropuertoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EventoBaseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EventoVueloDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.LoteEventosDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.TipoEvento;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class SimulacionJob implements Runnable {

    private final String simulacionId;
    private final LocalDate fechaInicio;
    private final int k;
    private final String algoritmo;
    private final PlanificadorService planificadorService;
    private final AeropuertoRepository aeropuertoRepository;
    private final WebSocketPublisher webSocketPublisher;
    private final AtomicBoolean pausada = new AtomicBoolean(false);
    private final AtomicBoolean detenida = new AtomicBoolean(false);
    private final LocalDateTime fechaCreacion = LocalDateTime.now();

    @Getter
    private final SimulacionState state;
    private volatile Thread hilo;

    public SimulacionJob(
            String simulacionId,
            LocalDate fechaInicio,
            int k,
            String algoritmo,
            long saMs,
            PlanificadorService planificadorService,
            AeropuertoRepository aeropuertoRepository,
            WebSocketPublisher webSocketPublisher
    ) {
        this.simulacionId = simulacionId;
        this.fechaInicio = fechaInicio;
        this.k = k;
        this.algoritmo = algoritmo;
        this.planificadorService = planificadorService;
        this.aeropuertoRepository = aeropuertoRepository;
        this.webSocketPublisher = webSocketPublisher;
        this.state = new SimulacionState(simulacionId, saMs);
        this.state.setTiempoSimuladoActual(fechaInicio.atStartOfDay());
    }

    public void asignarHilo(Thread hilo) {
        this.hilo = hilo;
    }

    @Override
    public void run() {
        state.setEstado("EN_PROCESO");

        try {
            publicarControl(TipoEvento.SIMULACION_INICIADA);
            dormir(700);
            verificarDetencion();

            List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();
            state.setAeropuertosSnapshot(aeropuertos.stream()
                    .collect(Collectors.toConcurrentMap(Aeropuerto::getCodigoIata, a -> a)));

            for (Aeropuerto aeropuerto : aeropuertos) {
                state.getInventarioSnapshot().put(aeropuerto.getCodigoIata(), 0);
            }

            publicarLoteAeropuertos(aeropuertos, "Estado inicial");

            SolucionRuta solucion = planificadorService.calcularSolucion(algoritmo, fechaInicio, k);
            state.setSolucionActual(solucion);
            cargarMaletasInicialesEnOrigenes(solucion);
            indexarEnviosPorVuelo(solucion);

            publicarLoteAeropuertos(aeropuertos, "Carga inicial");
            publicarControl(TipoEvento.PLAN_GENERADO);

            for (VueloAgrupado vuelo : agruparVuelos(solucion)) {
                esperarSiPausadaODetenida();
                procesarVuelo(vuelo);
            }

            state.setEstado("FINALIZADA");
            publicarControl(TipoEvento.SIMULACION_FINALIZADA);
        } catch (SimulacionDetenidaException e) {
            state.setEstado("DETENIDA");
            publicarControl(TipoEvento.SIMULACION_DETENIDA);
        } catch (Exception e) {
            state.setEstado("ERROR");
            publicarControl(TipoEvento.ERROR);
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
        Thread hiloActual = hilo;
        if (hiloActual != null) {
            hiloActual.interrupt();
        }
    }

    public void cambiarVelocidad(long saMs) {
        state.cambiarVelocidad(saMs);
        publicarControl(TipoEvento.VELOCIDAD_CAMBIADA);
    }

    public boolean estaPausada() {
        return pausada.get();
    }

    public boolean estaDetenida() {
        return detenida.get();
    }

    public String getAlgoritmo() {
        return algoritmo;
    }

    public int getK() {
        return k;
    }

    public LocalDate getFechaInicio() {
        return fechaInicio;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    private void procesarVuelo(VueloAgrupado vuelo) {
        state.setTiempoSimuladoActual(vuelo.fechaHoraSalida());

        List<EventoBaseDTO> salida = new ArrayList<>();
        restarMaletas(vuelo.origenIata(), vuelo.cantidadMaletas());
        agregarEventoAeropuerto(salida, vuelo.origenIata(), TipoEvento.AEROPUERTO_ACTUALIZADO);
        salida.add(crearEventoVuelo(vuelo, TipoEvento.VUELO_DESPEGA, "EN_VUELO"));
        publicarLote(salida, vuelo.fechaHoraSalida(), vuelo.fechaHoraSalida());

        esperarConControl();

        state.setTiempoSimuladoActual(vuelo.fechaHoraLlegada());

        List<EventoBaseDTO> llegada = new ArrayList<>();
        sumarMaletas(vuelo.destinoIata(), vuelo.cantidadMaletas());
        llegada.add(crearEventoVuelo(vuelo, TipoEvento.VUELO_ATERRIZA, "ATERRIZADO"));
        agregarEventoAeropuerto(llegada, vuelo.destinoIata(), TipoEvento.AEROPUERTO_ACTUALIZADO);
        publicarLote(llegada, vuelo.fechaHoraLlegada(), vuelo.fechaHoraLlegada());

        esperarConControl();
    }

    private void publicarLoteAeropuertos(List<Aeropuerto> aeropuertos, String estado) {
        esperarSiPausadaODetenida();
        List<EventoBaseDTO> eventos = new ArrayList<>();
        for (Aeropuerto aeropuerto : aeropuertos) {
            EventoAeropuertoDTO evento = crearEventoAeropuerto(aeropuerto, state.getInventarioSnapshot()
                    .getOrDefault(aeropuerto.getCodigoIata(), 0));
            evento.setEstado(estado + " " + obtenerEstadoSemaforo(evento.getPorcentajeOcupacion()));
            eventos.add(evento);
        }
        publicarLote(eventos, state.getTiempoSimuladoActual(), state.getTiempoSimuladoActual());
    }

    private void publicarControl(TipoEvento tipoEvento) {
        EventoBaseDTO evento = new EventoBaseDTO(tipoEvento, LocalDateTime.now().toString());
        LocalDateTime ventana = state.getTiempoSimuladoActual() != null
                ? state.getTiempoSimuladoActual()
                : LocalDateTime.now();
        publicarLote(List.of(evento), ventana, ventana);
    }

    private void publicarLote(List<EventoBaseDTO> eventos, LocalDateTime ventanaInicio, LocalDateTime ventanaFin) {
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

    private EventoVueloDTO crearEventoVuelo(VueloAgrupado vuelo, TipoEvento tipoEvento, String estado) {
        EventoVueloDTO evento = new EventoVueloDTO();
        evento.setTipo(tipoEvento);
        evento.setFechaHoraEvento(LocalDateTime.now().toString());
        evento.setCodigoVuelo(vuelo.codigoVuelo());
        evento.setOrigenIata(vuelo.origenIata());
        evento.setDestinoIata(vuelo.destinoIata());
        evento.setEstado(estado);
        evento.setCantidadMaletas(vuelo.cantidadMaletas());
        evento.setHoraSalida(vuelo.fechaHoraSalida().toString());
        evento.setHoraLlegada(vuelo.fechaHoraLlegada().toString());
        return evento;
    }

    private void agregarEventoAeropuerto(List<EventoBaseDTO> eventos, String codigoIata, TipoEvento tipoEvento) {
        Aeropuerto aeropuerto = state.getAeropuertosSnapshot().get(codigoIata);
        if (aeropuerto == null) return;

        EventoAeropuertoDTO evento = crearEventoAeropuerto(
                aeropuerto,
                state.getInventarioSnapshot().getOrDefault(codigoIata, 0)
        );
        evento.setTipo(tipoEvento);
        eventos.add(evento);
    }

    private EventoAeropuertoDTO crearEventoAeropuerto(Aeropuerto aeropuerto, int maletasActuales) {
        int capacidad = aeropuerto.getCapacidadAlmacen();
        double porcentaje = capacidad > 0 ? (maletasActuales * 100.0) / capacidad : 0.0;

        EventoAeropuertoDTO evento = new EventoAeropuertoDTO();
        evento.setTipo(TipoEvento.AEROPUERTO_ACTUALIZADO);
        evento.setFechaHoraEvento(LocalDateTime.now().toString());
        evento.setCodigoAeropuerto(aeropuerto.getCodigoIata());
        evento.setMaletasActuales(maletasActuales);
        evento.setCapacidadAlmacen(capacidad);
        evento.setPorcentajeOcupacion(porcentaje);
        evento.setEstado(obtenerEstadoSemaforo(porcentaje));
        return evento;
    }

    private void cargarMaletasInicialesEnOrigenes(SolucionRuta solucion) {
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;

            String origen = asignacion.getEnvio().getOrigenIata();
            int cantidad = asignacion.getEnvio().getCantidadMaletas();
            state.getInventarioSnapshot().merge(origen, cantidad, Integer::sum);
        }
    }

    private void indexarEnviosPorVuelo(SolucionRuta solucion) {
        Map<Long, List<EnvioDTO>> indice = new LinkedHashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;

            EnvioDTO envioDTO = convertirEnvio(asignacion.getEnvio());
            for (VueloInstanciado vuelo : asignacion.getItinerario().getVuelos()) {
                indice.computeIfAbsent(vuelo.getCodigoBase(), key -> new ArrayList<>()).add(envioDTO);
            }
        }

        state.setEnviosPorVuelo(indice);
    }

    private EnvioDTO convertirEnvio(Envio envio) {
        return new EnvioDTO(
                envio.getIdPedido(),
                envio.getOrigenIata(),
                envio.getDestinoIata(),
                envio.getFechaHora() != null ? envio.getFechaHora().toString() : null,
                envio.getCantidadMaletas(),
                envio.getIdCliente()
        );
    }

    private List<VueloAgrupado> agruparVuelos(SolucionRuta solucion) {
        Map<String, VueloAgrupadoAcumulado> agrupados = new LinkedHashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;

            int cantidadMaletas = asignacion.getEnvio().getCantidadMaletas();
            for (VueloInstanciado vuelo : asignacion.getItinerario().getVuelos()) {
                String key = vuelo.getCodigoBase()
                        + "|"
                        + vuelo.getOrigenIata()
                        + "|"
                        + vuelo.getDestinoIata()
                        + "|"
                        + vuelo.getFechaHoraSalida();

                agrupados.computeIfAbsent(key, k -> new VueloAgrupadoAcumulado(vuelo))
                        .sumar(cantidadMaletas);
            }
        }

        return agrupados.values()
                .stream()
                .map(VueloAgrupadoAcumulado::toVueloAgrupado)
                .sorted(Comparator.comparing(VueloAgrupado::fechaHoraSalida))
                .toList();
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
        if (detenida.get()) {
            throw new SimulacionDetenidaException();
        }
    }

    private boolean esTerminal() {
        return "FINALIZADA".equals(state.getEstado())
                || "DETENIDA".equals(state.getEstado())
                || "ERROR".equals(state.getEstado());
    }

    private void sumarMaletas(String aeropuerto, int cantidad) {
        state.getInventarioSnapshot().merge(aeropuerto, cantidad, Integer::sum);
    }

    private void restarMaletas(String aeropuerto, int cantidad) {
        int actual = state.getInventarioSnapshot().getOrDefault(aeropuerto, 0);
        state.getInventarioSnapshot().put(aeropuerto, Math.max(0, actual - cantidad));
    }

    private String obtenerEstadoSemaforo(double porcentaje) {
        if (porcentaje >= 90.0) return "ROJO";
        if (porcentaje >= 70.0) return "AMARILLO";
        return "VERDE";
    }

    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (detenida.get()) {
                throw new SimulacionDetenidaException();
            }
        }
    }

    private record VueloAgrupado(
            Long codigoVuelo,
            String origenIata,
            String destinoIata,
            LocalDateTime fechaHoraSalida,
            LocalDateTime fechaHoraLlegada,
            int cantidadMaletas
    ) {
    }

    private static class VueloAgrupadoAcumulado {
        private final VueloInstanciado vuelo;
        private int cantidadMaletas;

        private VueloAgrupadoAcumulado(VueloInstanciado vuelo) {
            this.vuelo = vuelo;
        }

        private void sumar(int cantidad) {
            cantidadMaletas += cantidad;
        }

        private VueloAgrupado toVueloAgrupado() {
            return new VueloAgrupado(
                    vuelo.getCodigoBase(),
                    vuelo.getOrigenIata(),
                    vuelo.getDestinoIata(),
                    vuelo.getFechaHoraSalida(),
                    vuelo.getFechaHoraLlegada(),
                    cantidadMaletas
            );
        }
    }

    private static class SimulacionDetenidaException extends RuntimeException {
    }
}
