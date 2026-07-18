package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioAeropuertoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

public class SimulacionEventosFactory {

    private final ConfiguracionColapsoDTO configuracionColapso;
    private static final double COTA_ROJO = 85.0;
    private static final double COTA_AMARILLO = 60.0;

    public SimulacionEventosFactory(ConfiguracionColapsoDTO configuracionColapso) {
        this.configuracionColapso = configuracionColapso;
    }

    public record ResultadoEventosVuelo(
            List<EventoBaseDTO> actuales,
            List<EventoBaseDTO> futuros
    ) {}

    public ResultadoEventosVuelo generarEventosVuelo(SolucionRuta solucion, Instant ventanaFin) {
        Map<String, EventoVueloDTO> actualesMap = new LinkedHashMap<>();
        Map<String, EventoVueloDTO> futurosMap = new LinkedHashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;
            int cantidadMaletas = asignacion.getEnvio().getCantidadMaletas();

            for (VueloInstanciado vuelo : asignacion.getItinerario().getVuelos()) {
                String keyDespega = "DESPEGA|" + vuelo.getCodigoBase() + "|" + vuelo.getFechaHoraSalida();
                String keyAterriza = "ATERRIZA|" + vuelo.getCodigoBase() + "|" + vuelo.getFechaHoraSalida();
                int capacidadMax = vuelo.getCapacidadMax(); // Asumiendo que tu VueloInstanciado tiene este getter

                // 1. Determinar a qué mapa va el DESPEGUE y actualizar
                Map<String, EventoVueloDTO> mapaDespegue = vuelo.getFechaHoraSalidaUtc().isAfter(ventanaFin) ? futurosMap : actualesMap;
                EventoVueloDTO evDespega = mapaDespegue.computeIfAbsent(keyDespega, k -> crearEventoVuelo(vuelo, TipoEvento.VUELO_DESPEGA));
                actualizarSemaforoVuelo(evDespega, cantidadMaletas, capacidadMax,List.of(asignacion.getEnvio().getIdPedido()));

                // 2. Determinar a qué mapa va el ATERRIZAJE y actualizar
                Map<String, EventoVueloDTO> mapaAterrizaje = vuelo.getFechaHoraLlegadaUtc().isAfter(ventanaFin) ? futurosMap : actualesMap;
                EventoVueloDTO evAterriza = mapaAterrizaje.computeIfAbsent(keyAterriza, k -> crearEventoVuelo(vuelo, TipoEvento.VUELO_ATERRIZA));
                actualizarSemaforoVuelo(evAterriza, cantidadMaletas, capacidadMax, List.of(asignacion.getEnvio().getIdPedido()));
            }
        }
        return new ResultadoEventosVuelo(
                new ArrayList<>(actualesMap.values()),
                new ArrayList<>(futurosMap.values())
        );
    }

    public void actualizarSemaforoVuelo(EventoVueloDTO dto, int nuevasMaletas, int capacidadMax, List<String> envios) {
        dto.setCapacidadMax(capacidadMax);
        int totalMaletas = dto.getCantidadMaletas() + nuevasMaletas;
        dto.setCantidadMaletas(totalMaletas);
        dto.getCodigoEnvios().addAll(envios);

        double porcentaje = capacidadMax > 0 ? (totalMaletas * 100.0) / capacidadMax : 0.0;
        dto.setPorcentajeOcupacion(porcentaje);

        if (porcentaje >= COTA_ROJO) {
            dto.setEstado(EstadoCapacidad.ROJO);
        } else if (porcentaje >= COTA_AMARILLO) {
            dto.setEstado(EstadoCapacidad.AMARILLO);
        } else {
            dto.setEstado(EstadoCapacidad.VERDE);
        }
    }

    public void fusionarEventoVuelo(EventoVueloDTO existente, EventoVueloDTO nuevo) {
        // Ambos eventos representan fotografias completas del mismo vuelo fisico. El evento
        // futuro se regenera en cada bloque; sumarlo como delta duplicaba carga y envios.
        existente.setCantidadMaletas(nuevo.getCantidadMaletas());
        existente.setCapacidadMax(nuevo.getCapacidadMax());
        existente.setPorcentajeOcupacion(nuevo.getPorcentajeOcupacion());
        existente.setEstado(nuevo.getEstado());
        existente.setCodigoEnvios(new ArrayList<>(nuevo.getCodigoEnvios()));
    }

    public EventoVueloDTO crearEventoVuelo(VueloInstanciado vuelo, TipoEvento tipoEvento) {

        Instant tiempoSimulado = (tipoEvento == TipoEvento.VUELO_DESPEGA || tipoEvento == TipoEvento.VUELO_CANCELADO)
                ? vuelo.getFechaHoraSalidaUtc()
                : vuelo.getFechaHoraLlegadaUtc();
        return new EventoVueloDTO(
                tipoEvento,
                tiempoSimulado.toString(),
                vuelo.getCodigoBase(),
                vuelo.getOrigenIata(),
                vuelo.getDestinoIata(),
                EstadoCapacidad.VERDE,
                0,
                vuelo.getFechaHoraSalida().toString(),
                vuelo.getFechaHoraLlegada().toString(),
                vuelo.getFechaHoraSalidaUtc().toString(),
                vuelo.getFechaHoraLlegadaUtc().toString(),
                new ArrayList<String>()
        );
    }

    public EventoVueloDTO crearEventoVueloCancelado(VueloInstanciado vuelo) {
        EventoVueloDTO evento = crearEventoVuelo(vuelo, TipoEvento.VUELO_CANCELADO);
        evento.setEstado(EstadoCapacidad.ROJO);
        evento.setMotivo(vuelo.getMotivoCancelacion());
        return evento;
    }

    public EventoAeropuertoDTO crearEventoAeropuerto(Aeropuerto aeropuerto, int maletasActuales, Instant tiempoEvento, SimulacionState state) {
        int capacidad = aeropuerto.getCapacidadAlmacen();
        double porcentaje = capacidad > 0 ? (maletasActuales * 100.0) / capacidad : 0.0;
        EstadoCapacidad estadoSemaforo = calcularEstadoAeropuerto(maletasActuales, capacidad);
        boolean consistente = (maletasActuales <= 0) == (estadoSemaforo == EstadoCapacidad.VACIO);
        if (!consistente) {
            System.err.println("[AIRPORT-STATE-AUDIT] simulacionId=" + state.getSimulacionId()
                    + " bloque=" + state.getBloquesProcesados()
                    + " iata=" + aeropuerto.getCodigoIata()
                    + " capacidad=" + capacidad
                    + " ocupacionInventarioBackend=" + maletasActuales
                    + " ocupacionDto=" + maletasActuales
                    + " estadoBackend=" + estadoSemaforo
                    + " estadoDto=" + estadoSemaforo
                    + " porcentaje=" + porcentaje
                    + " timestampSimulado=" + tiempoEvento
                    + " coincide=false");
        }

        String codigoIata = aeropuerto.getCodigoIata();

        // Calcular en caliente los 5 envíos más críticos físicamente en este aeropuerto
        List<EnvioAeropuertoDTO> top5Envios = state.getUltimoAeropuertoPorEnvio().entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(codigoIata))
                .map(Map.Entry::getKey)
                .filter(idPedido -> !state.getEnviosEntregados().contains(idPedido))
                .map(id -> state.getEnviosEnSeguimiento().get(id))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(asignacion ->
                        pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils.calcularDeadlineSla(asignacion.getEnvio(), state.getAeropuertosSnapshot())
                ))
                .limit(5)
                .map(asignacion -> {
                    pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio e = asignacion.getEnvio();
                    var itinerario = asignacion.getItinerario();
                    return new EnvioAeropuertoDTO(
                            new EnvioDTO(
                                    e.getIdPedido(), e.getOrigenIata(), e.getDestinoIata(),
                                    e.getFechaHora() != null ? e.getFechaHora().toString() : null,
                                    e.getCantidadMaletas(), e.getIdCliente(), e.isEsOperacionDia()
                            ),
                            itinerario != null ? itinerario.getFechaHoraSalidaUtc() : null,
                            itinerario != null ? itinerario.getFechaHoraLlegadaUtc() : null
                    );
                })
                .toList();

        return new EventoAeropuertoDTO(
                TipoEvento.AEROPUERTO_ACTUALIZADO, tiempoEvento.toString(), codigoIata,
                estadoSemaforo, porcentaje, maletasActuales, capacidad, top5Envios);
    }

    public static EstadoCapacidad calcularEstadoAeropuerto(int ocupacion, int capacidad) {
        if (ocupacion <= 0) return EstadoCapacidad.VACIO;
        double porcentaje = capacidad > 0 ? ocupacion * 100.0 / capacidad : 0.0;
        if (porcentaje >= COTA_ROJO) return EstadoCapacidad.ROJO;
        if (porcentaje >= COTA_AMARILLO) return EstadoCapacidad.AMARILLO;
        return EstadoCapacidad.VERDE;
    }

    public EventoAeropuertoDTO crearAlertaAeropuertoSaturado(EventoAeropuertoDTO evento) {
        return new EventoAeropuertoDTO(
                TipoEvento.ALERTA_AEROPUERTO_SATURADO,
                evento.getFechaHoraEvento(),
                evento.getCodigoAeropuerto(),
                evento.getEstadoCapacidad(),
                evento.getPorcentajeOcupacion(),
                evento.getMaletasActuales(),
                evento.getCapacidadAlmacen()
        );
    }

    public MetricasColapsoDTO calcularMetricasColapso(
            int ciclo, LocalDateTime ventanaInicio, LocalDateTime ventanaFin,
            List<Envio> nuevos, List<Envio> pendientes, List<Envio> enviosAProcesar,
            SolucionRuta solucion, Map<String, Aeropuerto> mapaAeropuertos
    ) {
        int enviosProcesados = enviosAProcesar.size();
        int maletasProcesadas = nuevos.stream().mapToInt(Envio::getCantidadMaletas).sum()
                + pendientes.stream().mapToInt(Envio::getCantidadMaletas).sum();

        int enviosSinItinerario = solucion.getSinItinerarioCount();
        int slaIncumplidos = solucion.getExcedeSlaCount();

        // Las métricas de colapso usan proporciones 0..1, no porcentajes 0..100.
        Map<VueloInstanciado, Integer> cargaPorVuelo = new LinkedHashMap<>();
        Map<String, Integer> cargaPorAeropuerto = new LinkedHashMap<>();
        for (RutaAsignada asig : solucion.getAsignaciones()) {
            if (asig.getItinerario() == null) {
                cargaPorAeropuerto.merge(asig.getEnvio().getOrigenIata(), asig.getEnvio().getCantidadMaletas(), Integer::sum);
                continue;
            }
            cargaPorAeropuerto.merge(asig.getItinerario().getDestinoIata(), asig.getEnvio().getCantidadMaletas(), Integer::sum);
            for (VueloInstanciado v : asig.getItinerario().getVuelos()) {
                cargaPorVuelo.merge(v, asig.getEnvio().getCantidadMaletas(), Integer::sum);
            }
        }
        int vuelosSobrecargados = (int) cargaPorVuelo.entrySet().stream()
                .filter(entry -> entry.getValue() > entry.getKey().getCapacidadMax()).count();
        double ocupacionAeropuertoMaxima = 0.0;
        int aeropuertosSaturados = 0;
        for (Map.Entry<String, Integer> entry : cargaPorAeropuerto.entrySet()) {
            Aeropuerto aeropuerto = mapaAeropuertos.get(entry.getKey());
            if (aeropuerto == null || aeropuerto.getCapacidadAlmacen() <= 0) {
                continue;
            }
            double ocupacion = entry.getValue() / (double) aeropuerto.getCapacidadAlmacen();
            ocupacionAeropuertoMaxima = Math.max(ocupacionAeropuertoMaxima, ocupacion);
            if (ocupacion >= 1.0) {
                aeropuertosSaturados++;
            }
        }

        MetricasColapsoDTO metricas = new MetricasColapsoDTO();
        metricas.setCiclo(ciclo);
        metricas.setVentanaInicio(ventanaInicio.toString());
        metricas.setVentanaFin(ventanaFin.toString());
        metricas.setEnviosNuevos(nuevos.size());
        metricas.setMaletasNuevas(nuevos.stream().mapToInt(Envio::getCantidadMaletas).sum());
        metricas.setEnviosPendientes(pendientes.size());
        metricas.setMaletasPendientes(pendientes.stream().mapToInt(Envio::getCantidadMaletas).sum());
        metricas.setEnviosProcesados(enviosProcesados);
        metricas.setMaletasProcesadas(maletasProcesadas);
        metricas.setEnviosSinItinerario(enviosSinItinerario);
        metricas.setPorcentajeSinItinerario(enviosProcesados > 0 ? enviosSinItinerario / (double) enviosProcesados : 0.0);
        metricas.setSlaIncumplidos(slaIncumplidos);
        metricas.setEnviosSlaIncumplidos(slaIncumplidos);
        metricas.setPorcentajeSlaIncumplido(enviosProcesados > 0 ? slaIncumplidos / (double) enviosProcesados : 0.0);
        metricas.setVuelosSobrecargados(vuelosSobrecargados);
        metricas.setAeropuertosSaturados(aeropuertosSaturados);
        metricas.setOcupacionAeropuertoMaxima(ocupacionAeropuertoMaxima);
        metricas.setFitnessUltimaSolucion(solucion.getFitness());
        return metricas;
    }

    public List<String> detectarCriteriosColapso(MetricasColapsoDTO metricas) {
        List<String> criterios = new ArrayList<>();

        if (configuracionColapso == null || metricas == null) {
            return criterios;
        }

        if (metricas.getEnviosSinItinerario() > 0
                && superaUmbral(metricas.getPorcentajeSinItinerario(), configuracionColapso.getUmbralSinItinerario())) {
            criterios.add("PORCENTAJE_SIN_ITINERARIO");
        }
        if (metricas.getSlaIncumplidos() > 0
                && superaUmbral(metricas.getPorcentajeSlaIncumplido(), configuracionColapso.getUmbralSla())) {
            criterios.add("PORCENTAJE_SLA_INCUMPLIDO");
        }
        if (metricas.getAeropuertosSaturados() > 0
                || superaUmbral(metricas.getOcupacionAeropuertoMaxima(), configuracionColapso.getUmbralAeropuerto())) {
            criterios.add("ALERTA_AEROPUERTO_SATURADO");
        }
        if (metricas.getVuelosSobrecargados() > 0) {
            criterios.add("VUELOS_SOBRECARGADOS");
        }

        return criterios;
    }

    private boolean superaUmbral(double valor, double umbral) {
        return valor > 0.0 && valor >= umbral;
    }


}
