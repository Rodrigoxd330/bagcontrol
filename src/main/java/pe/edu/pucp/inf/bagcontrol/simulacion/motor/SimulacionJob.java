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
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EventoAeropuertoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EventoBaseDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EventoCicloColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EventoColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EventoVueloDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.LoteEventosDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.TipoEvento;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private final ConfiguracionColapsoDTO configuracionColapso;
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
        this.configuracionColapso = null;
        this.planificadorService = planificadorService;
        this.aeropuertoRepository = aeropuertoRepository;
        this.webSocketPublisher = webSocketPublisher;
        this.state = new SimulacionState(simulacionId, saMs);
        this.state.setTiempoSimuladoActual(fechaInicio.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    public SimulacionJob(
            String simulacionId,
            LocalDate fechaInicio,
            int k,
            String algoritmo,
            long saMs,
            PlanificadorService planificadorService,
            AeropuertoRepository aeropuertoRepository,
            WebSocketPublisher webSocketPublisher,
            ConfiguracionColapsoDTO configuracionColapso
    ) {
        this.simulacionId = simulacionId;
        this.fechaInicio = fechaInicio;
        this.k = k;
        this.algoritmo = algoritmo;
        this.configuracionColapso = configuracionColapso;
        this.planificadorService = planificadorService;
        this.aeropuertoRepository = aeropuertoRepository;
        this.webSocketPublisher = webSocketPublisher;
        this.state = new SimulacionState(simulacionId, saMs);
        this.state.setModoSimulacion("COLAPSO");
        this.state.setEstadoColapso("EN_EVALUACION");
        this.state.setTiempoSimuladoActual(fechaInicio.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    public void asignarHilo(Thread hilo) {
        this.hilo = hilo;
    }

    @Override
    public void run() {
        state.setEstado("EN_PROCESO");

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
            state.setEstado("ERROR");
            publicarControl(TipoEvento.ERROR);
        }
    }

    private void ejecutarModoNormal() {
        long inicioTotal = System.currentTimeMillis();
        publicarControl(TipoEvento.SIMULACION_INICIADA);
        dormir(700);
        verificarDetencion();

        List<Aeropuerto> aeropuertos = inicializarAeropuertos();
        publicarLoteAeropuertos(aeropuertos, "Estado inicial");

        long inicioCalcularSolucion = System.currentTimeMillis();
        SolucionRuta solucion = planificadorService.calcularSolucion(algoritmo, fechaInicio, k);
        long tiempoCalcularSolucion = System.currentTimeMillis() - inicioCalcularSolucion;
        state.setSolucionActual(solucion);
        cargarMaletasInicialesEnOrigenes(solucion);
        indexarEnviosPorVuelo(solucion);

        publicarLoteAeropuertos(aeropuertos, "Carga inicial");
        publicarControl(TipoEvento.PLAN_GENERADO);

        long inicioAgrupacion = System.currentTimeMillis();
        List<VueloAgrupado> vuelosAgrupados = agruparVuelos(solucion);
        long tiempoAgrupacion = System.currentTimeMillis() - inicioAgrupacion;
        int eventosProgramados = vuelosAgrupados.size() * 2;

        procesarEventosProgramados(vuelosAgrupados);

        state.setEstado("FINALIZADA");
        publicarControl(TipoEvento.SIMULACION_FINALIZADA);
        long tiempoTotal = System.currentTimeMillis() - inicioTotal;
        System.out.println("[METRICA SIMULACION] simulacionId=" + simulacionId
                + " modo=NORMAL"
                + " algoritmo=" + algoritmo
                + " fechaInicio=" + fechaInicio
                + " k=" + k
                + " asignaciones=" + solucion.getAsignaciones().size()
                + " vuelosAgrupados=" + vuelosAgrupados.size()
                + " eventosProgramados=" + eventosProgramados
                + " lotesEmitidos=" + state.getUltimoLoteEmitidoNumero().get()
                + " tiempoCalcularSolucionMs=" + tiempoCalcularSolucion
                + " tiempoAgrupacionVuelosMs=" + tiempoAgrupacion
                + " tiempoTotalSimulacionMs=" + tiempoTotal);
    }

    private void ejecutarModoColapso() {
        long inicioTotalColapso = System.currentTimeMillis();
        publicarControl(TipoEvento.SIMULACION_INICIADA);
        dormir(700);
        verificarDetencion();

        List<Aeropuerto> aeropuertos = inicializarAeropuertos();
        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));
        publicarLoteAeropuertos(aeropuertos, "Estado inicial");

        List<Envio> pendientes = new ArrayList<>();
        int ciclosPendientesCreciendo = 0;
        int ciclosVuelosSobrecargados = 0;
        int pendientesPrevios = 0;
        int ciclo = 0;
        String motivoFinal = null;

        for (int diaOffset = 0; diaOffset < configuracionColapso.getMaxDias();
             diaOffset += configuracionColapso.getTamanoCicloDias()) {
            long inicioCiclo = System.currentTimeMillis();
            esperarSiPausadaODetenida();
            ciclo++;

            LocalDate ventanaInicio = fechaInicio.plusDays(diaOffset);
            int diasCiclo = Math.min(
                    configuracionColapso.getTamanoCicloDias(),
                    configuracionColapso.getMaxDias() - diaOffset
            );
            LocalDate ventanaFin = ventanaInicio.plusDays(diasCiclo);
            state.setCicloActual(ciclo);
            state.setTiempoSimuladoActual(ventanaInicio.atStartOfDay().toInstant(ZoneOffset.UTC));

            List<Envio> nuevos = planificadorService.obtenerEnviosEnVentana(
                    ventanaInicio.atStartOfDay(),
                    ventanaFin.atStartOfDay()
            );

            List<Envio> enviosAProcesar = new ArrayList<>(pendientes);
            enviosAProcesar.addAll(nuevos);

            long inicioPlanificacionCiclo = System.currentTimeMillis();
            SolucionRuta solucion = planificadorService.calcularSolucionParaEnvios(
                    algoritmo,
                    ventanaInicio,
                    diasCiclo,
                    enviosAProcesar
            );
            long tiempoPlanificacionCiclo = System.currentTimeMillis() - inicioPlanificacionCiclo;

            state.setSolucionActual(solucion);
            indexarEnviosPorVuelo(solucion);

            pendientes = obtenerPendientes(solucion);
            if (pendientes.size() > pendientesPrevios) {
                ciclosPendientesCreciendo++;
            } else {
                ciclosPendientesCreciendo = 0;
            }
            pendientesPrevios = pendientes.size();

            MetricasColapsoDTO metricas = calcularMetricasColapso(
                    ciclo,
                    ventanaInicio,
                    ventanaFin,
                    nuevos,
                    pendientes,
                    enviosAProcesar,
                    solucion,
                    mapaAeropuertos
            );

            if (metricas.getVuelosSobrecargados() > 0) {
                ciclosVuelosSobrecargados++;
            } else {
                ciclosVuelosSobrecargados = 0;
            }

            List<String> criterios = detectarCriteriosColapso(
                    metricas,
                    ciclosPendientesCreciendo,
                    ciclosVuelosSobrecargados
            );

            if (!criterios.isEmpty()) {
                metricas.setMotivoColapso(criterios.get(0));
            }

            actualizarInventarioColapso(solucion, mapaAeropuertos);
            state.setMetricasColapsoActuales(metricas);
            publicarLote(
                    List.of(new EventoCicloColapsoDTO(LocalDateTime.now().toString(), metricas)),
                    state.getTiempoSimuladoActual(),
                    ventanaFin.atStartOfDay().toInstant(ZoneOffset.UTC)
            );
            long tiempoTotalCiclo = System.currentTimeMillis() - inicioCiclo;
            imprimirMetricasCicloColapso(metricas, !criterios.isEmpty(), tiempoPlanificacionCiclo, tiempoTotalCiclo);

            if (!criterios.isEmpty()) {
                motivoFinal = criterios.get(0);
                state.setEstadoColapso("COLAPSO_DETECTADO");
                state.setMotivoColapso(criterios.get(0));
                publicarLote(
                        List.of(new EventoColapsoDTO(
                                LocalDateTime.now().toString(),
                                simulacionId,
                                state.getTiempoSimuladoActual().toString(),
                                ciclo,
                                criterios.get(0),
                                criterios,
                                metricas
                        )),
                        state.getTiempoSimuladoActual(),
                        state.getTiempoSimuladoActual()
                );
                state.setEstado("FINALIZADA");
                publicarControl(TipoEvento.SIMULACION_FINALIZADA);
                imprimirMetricasFinalesColapso(ciclo, state.getEstadoColapso(), motivoFinal, inicioTotalColapso);
                return;
            }

            esperarConControl();
        }

        state.setEstadoColapso("NO_DETECTADO");
        state.setEstado("FINALIZADA");
        publicarControl(TipoEvento.SIMULACION_FINALIZADA);
        imprimirMetricasFinalesColapso(ciclo, state.getEstadoColapso(), motivoFinal, inicioTotalColapso);
    }

    private List<Aeropuerto> inicializarAeropuertos() {
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();
        state.setAeropuertosSnapshot(aeropuertos.stream()
                .collect(Collectors.toConcurrentMap(Aeropuerto::getCodigoIata, a -> a)));

        for (Aeropuerto aeropuerto : aeropuertos) {
            state.getInventarioSnapshot().put(aeropuerto.getCodigoIata(), 0);
        }

        return aeropuertos;
    }

    private List<Envio> obtenerPendientes(SolucionRuta solucion) {
        return solucion.getAsignaciones().stream()
                .filter(asignacion -> asignacion.getItinerario() == null)
                .map(RutaAsignada::getEnvio)
                .toList();
    }

    private MetricasColapsoDTO calcularMetricasColapso(
            int ciclo,
            LocalDate ventanaInicio,
            LocalDate ventanaFin,
            List<Envio> nuevos,
            List<Envio> pendientes,
            List<Envio> enviosAProcesar,
            SolucionRuta solucion,
            Map<String, Aeropuerto> mapaAeropuertos
    ) {
        int enviosProcesados = enviosAProcesar.size();
        int maletasProcesadas = sumarMaletas(enviosAProcesar);
        int enviosSinItinerario = solucion.getSinItinerarioCount();
        int maletasSinItinerario = sumarMaletas(pendientes);
        int slaIncumplidos = solucion.getExcedeSlaCount();
        Map<VueloInstanciado, Integer> cargaPorVuelo = calcularCargaPorVuelo(solucion);
        Map<String, Integer> cargaPorAeropuerto = calcularCargaPorAeropuerto(solucion);

        int vuelosSobrecargados = (int) cargaPorVuelo.entrySet().stream()
                .filter(entry -> entry.getValue() > entry.getKey().getCapacidadMax())
                .count();

        int aeropuertosSaturados = 0;
        double ocupacionAeropuertoMaxima = 0.0;
        for (Map.Entry<String, Integer> entry : cargaPorAeropuerto.entrySet()) {
            Aeropuerto aeropuerto = mapaAeropuertos.get(entry.getKey());
            if (aeropuerto == null || aeropuerto.getCapacidadAlmacen() <= 0) continue;

            double ocupacion = entry.getValue() / (double) aeropuerto.getCapacidadAlmacen();
            ocupacionAeropuertoMaxima = Math.max(ocupacionAeropuertoMaxima, ocupacion);
            if (ocupacion >= configuracionColapso.getUmbralAeropuerto()) {
                aeropuertosSaturados++;
            }
        }

        return new MetricasColapsoDTO(
                ciclo,
                ventanaInicio.toString(),
                ventanaFin.toString(),
                nuevos.size(),
                sumarMaletas(nuevos),
                pendientes.size(),
                sumarMaletas(pendientes),
                enviosProcesados,
                maletasProcesadas,
                enviosSinItinerario,
                maletasSinItinerario,
                enviosProcesados > 0 ? enviosSinItinerario / (double) enviosProcesados : 0.0,
                slaIncumplidos,
                enviosProcesados > 0 ? slaIncumplidos / (double) enviosProcesados : 0.0,
                vuelosSobrecargados,
                aeropuertosSaturados,
                ocupacionAeropuertoMaxima,
                solucion.getFitness(),
                null
        );
    }

    private List<String> detectarCriteriosColapso(
            MetricasColapsoDTO metricas,
            int ciclosPendientesCreciendo,
            int ciclosVuelosSobrecargados
    ) {
        List<String> criterios = new ArrayList<>();

        if (metricas.getPorcentajeSinItinerario() >= configuracionColapso.getUmbralSinItinerario()) {
            criterios.add("PORCENTAJE_SIN_ITINERARIO");
        }
        if (metricas.getPorcentajeSlaIncumplido() >= configuracionColapso.getUmbralSla()) {
            criterios.add("PORCENTAJE_SLA_INCUMPLIDO");
        }
        if (metricas.getOcupacionAeropuertoMaxima() >= configuracionColapso.getUmbralAeropuerto()) {
            criterios.add("AEROPUERTO_SATURADO");
        }
        if (ciclosPendientesCreciendo >= configuracionColapso.getCiclosPendientesCrecientes()) {
            criterios.add("PENDIENTES_CRECEN_CONSECUTIVAMENTE");
        }
        if (ciclosVuelosSobrecargados >= configuracionColapso.getCiclosSobrecargaVuelo()) {
            criterios.add("VUELOS_SOBRECARGADOS_CONSECUTIVOS");
        }

        return criterios;
    }

    private int sumarMaletas(List<Envio> envios) {
        return envios.stream().mapToInt(Envio::getCantidadMaletas).sum();
    }

    private Map<VueloInstanciado, Integer> calcularCargaPorVuelo(SolucionRuta solucion) {
        Map<VueloInstanciado, Integer> cargaPorVuelo = new LinkedHashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;

            int cantidadMaletas = asignacion.getEnvio().getCantidadMaletas();
            for (VueloInstanciado vuelo : asignacion.getItinerario().getVuelos()) {
                cargaPorVuelo.merge(vuelo, cantidadMaletas, Integer::sum);
            }
        }

        return cargaPorVuelo;
    }

    private Map<String, Integer> calcularCargaPorAeropuerto(SolucionRuta solucion) {
        Map<String, Integer> cargaPorAeropuerto = new LinkedHashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            int cantidadMaletas = asignacion.getEnvio().getCantidadMaletas();
            if (asignacion.getItinerario() == null) {
                cargaPorAeropuerto.merge(asignacion.getEnvio().getOrigenIata(), cantidadMaletas, Integer::sum);
            } else {
                cargaPorAeropuerto.merge(asignacion.getItinerario().getDestinoIata(), cantidadMaletas, Integer::sum);
            }
        }

        return cargaPorAeropuerto;
    }

    private void actualizarInventarioColapso(SolucionRuta solucion, Map<String, Aeropuerto> mapaAeropuertos) {
        Map<String, Integer> cargaPorAeropuerto = calcularCargaPorAeropuerto(solucion);

        for (String codigoIata : mapaAeropuertos.keySet()) {
            state.getInventarioSnapshot().put(codigoIata, cargaPorAeropuerto.getOrDefault(codigoIata, 0));
        }
    }

    private void imprimirMetricasCicloColapso(
            MetricasColapsoDTO metricas,
            boolean huboColapso,
            long tiempoPlanificacionCiclo,
            long tiempoTotalCiclo
    ) {
        System.out.println("[METRICA COLAPSO] simulacionId=" + simulacionId
                + " ciclo=" + metricas.getCiclo()
                + " ventanaInicio=" + metricas.getVentanaInicio()
                + " ventanaFin=" + metricas.getVentanaFin()
                + " enviosNuevos=" + metricas.getEnviosNuevos()
                + " maletasNuevas=" + metricas.getMaletasNuevas()
                + " enviosPendientes=" + metricas.getEnviosPendientes()
                + " maletasPendientes=" + metricas.getMaletasPendientes()
                + " enviosProcesados=" + metricas.getEnviosProcesados()
                + " maletasProcesadas=" + metricas.getMaletasProcesadas()
                + " enviosSinItinerario=" + metricas.getEnviosSinItinerario()
                + " porcentajeSinItinerario=" + metricas.getPorcentajeSinItinerario()
                + " slaIncumplidos=" + metricas.getSlaIncumplidos()
                + " porcentajeSlaIncumplido=" + metricas.getPorcentajeSlaIncumplido()
                + " vuelosSobrecargados=" + metricas.getVuelosSobrecargados()
                + " aeropuertosSaturados=" + metricas.getAeropuertosSaturados()
                + " ocupacionAeropuertoMaxima=" + metricas.getOcupacionAeropuertoMaxima()
                + " fitnessUltimaSolucion=" + metricas.getFitnessUltimaSolucion()
                + " huboColapso=" + huboColapso
                + " motivoColapso=" + metricas.getMotivoColapso()
                + " tiempoPlanificacionCicloMs=" + tiempoPlanificacionCiclo
                + " tiempoTotalCicloMs=" + tiempoTotalCiclo);
    }

    private void imprimirMetricasFinalesColapso(
            int ciclosEjecutados,
            String estadoFinal,
            String motivoColapso,
            long inicioTotalColapso
    ) {
        long tiempoTotal = System.currentTimeMillis() - inicioTotalColapso;
        System.out.println("[METRICA COLAPSO] simulacionId=" + simulacionId
                + " resumenFinal=true"
                + " ciclosEjecutados=" + ciclosEjecutados
                + " estadoFinal=" + estadoFinal
                + " motivoColapso=" + motivoColapso
                + " totalLotesEmitidos=" + state.getUltimoLoteEmitidoNumero().get()
                + " tiempoTotalMs=" + tiempoTotal);
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

    private void procesarEventosProgramados(List<VueloAgrupado> vuelos) {
        List<EventoProgramado> eventosProgramados = crearEventosProgramados(vuelos);

        int indice = 0;
        while (indice < eventosProgramados.size()) {
            esperarSiPausadaODetenida();

            Instant instantUtc = eventosProgramados.get(indice).instantUtc();
            state.setTiempoSimuladoActual(instantUtc);

            List<EventoBaseDTO> eventos = new ArrayList<>();
            while (indice < eventosProgramados.size()
                    && eventosProgramados.get(indice).instantUtc().equals(instantUtc)) {
                procesarEventoProgramado(eventosProgramados.get(indice), eventos);
                indice++;
            }

            publicarLote(eventos, instantUtc, instantUtc);
            esperarConControl();
        }
    }

    private List<EventoProgramado> crearEventosProgramados(List<VueloAgrupado> vuelos) {
        return vuelos.stream()
                .flatMap(vuelo -> List.of(
                        new EventoProgramado(TipoEvento.VUELO_DESPEGA, vuelo.fechaHoraSalidaUtc(), vuelo),
                        new EventoProgramado(TipoEvento.VUELO_ATERRIZA, vuelo.fechaHoraLlegadaUtc(), vuelo)
                ).stream())
                .sorted(Comparator.comparing(EventoProgramado::instantUtc))
                .toList();
    }

    private void procesarEventoProgramado(EventoProgramado eventoProgramado, List<EventoBaseDTO> eventos) {
        VueloAgrupado vuelo = eventoProgramado.vuelo();

        if (eventoProgramado.tipo() == TipoEvento.VUELO_DESPEGA) {
            restarMaletas(vuelo.origenIata(), vuelo.cantidadMaletas());
            agregarEventoAeropuerto(eventos, vuelo.origenIata(), TipoEvento.AEROPUERTO_ACTUALIZADO);
            eventos.add(crearEventoVuelo(vuelo, TipoEvento.VUELO_DESPEGA, "EN_VUELO"));
            return;
        }

        if (eventoProgramado.tipo() == TipoEvento.VUELO_ATERRIZA) {
            sumarMaletas(vuelo.destinoIata(), vuelo.cantidadMaletas());
            eventos.add(crearEventoVuelo(vuelo, TipoEvento.VUELO_ATERRIZA, "ATERRIZADO"));
            agregarEventoAeropuerto(eventos, vuelo.destinoIata(), TipoEvento.AEROPUERTO_ACTUALIZADO);
        }
    }

    private void publicarLoteAeropuertos(List<Aeropuerto> aeropuertos, String estado) {
        esperarSiPausadaODetenida();
        List<EventoBaseDTO> eventos = new ArrayList<>();
        for (Aeropuerto aeropuerto : aeropuertos) {
            EventoAeropuertoDTO evento = crearEventoAeropuerto(aeropuerto, state.getInventarioSnapshot()
                    .getOrDefault(aeropuerto.getCodigoIata(), 0));
            evento.setEstado(obtenerEstadoSemaforo(evento.getPorcentajeOcupacion()));
            evento.setMensaje(estado);
            eventos.add(evento);
        }
        publicarLote(eventos, state.getTiempoSimuladoActual(), state.getTiempoSimuladoActual());
    }

    private void publicarControl(TipoEvento tipoEvento) {
        EventoBaseDTO evento = new EventoBaseDTO(tipoEvento, LocalDateTime.now().toString());
        Instant ventana = state.getTiempoSimuladoActual() != null
                ? state.getTiempoSimuladoActual()
                : Instant.now();
        publicarLote(List.of(evento), ventana, ventana);
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
        evento.setHoraSalidaLocal(vuelo.fechaHoraSalida().toString());
        evento.setHoraLlegadaLocal(vuelo.fechaHoraLlegada().toString());
        evento.setHoraSalidaUtc(vuelo.fechaHoraSalidaUtc().toString());
        evento.setHoraLlegadaUtc(vuelo.fechaHoraLlegadaUtc().toString());
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
                .sorted(Comparator.comparing(VueloAgrupado::fechaHoraSalidaUtc))
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
            Instant fechaHoraSalidaUtc,
            Instant fechaHoraLlegadaUtc,
            int cantidadMaletas
    ) {
    }

    private record EventoProgramado(
            TipoEvento tipo,
            Instant instantUtc,
            VueloAgrupado vuelo
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
                    vuelo.getFechaHoraSalidaUtc(),
                    vuelo.getFechaHoraLlegadaUtc(),
                    cantidadMaletas
            );
        }
    }

    private static class SimulacionDetenidaException extends RuntimeException {
    }
}
