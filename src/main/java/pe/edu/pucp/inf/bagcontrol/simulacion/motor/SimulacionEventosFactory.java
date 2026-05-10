package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoAeropuertoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.EventoVueloDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.TipoEvento;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

public class SimulacionEventosFactory {

    private final ConfiguracionColapsoDTO configuracionColapso;

    public SimulacionEventosFactory(ConfiguracionColapsoDTO configuracionColapso) {
        this.configuracionColapso = configuracionColapso;
    }

    public List<EventoProgramado> generarLineaDeTiempo(SolucionRuta solucion) {
        Map<String, VueloAgrupadoAcumulado> agrupados = new LinkedHashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;
            int cantidadMaletas = asignacion.getEnvio().getCantidadMaletas();
            for (VueloInstanciado vuelo : asignacion.getItinerario().getVuelos()) {
                String key = vuelo.getCodigoBase() + "|" + vuelo.getOrigenIata() + "|"
                        + vuelo.getDestinoIata() + "|" + vuelo.getFechaHoraSalida();
                agrupados.computeIfAbsent(key, k -> new VueloAgrupadoAcumulado(vuelo)).sumar(cantidadMaletas);
            }
        }

        List<VueloAgrupado> vuelosAgrupados = agrupados.values().stream()
                .map(VueloAgrupadoAcumulado::toVueloAgrupado)
                .sorted(Comparator.comparing(VueloAgrupado::fechaHoraSalidaUtc))
                .toList();

        return vuelosAgrupados.stream()
                .flatMap(vuelo -> List.of(
                        new EventoProgramado(TipoEvento.VUELO_DESPEGA, vuelo.fechaHoraSalidaUtc(), vuelo),
                        new EventoProgramado(TipoEvento.VUELO_ATERRIZA, vuelo.fechaHoraLlegadaUtc(), vuelo)
                ).stream())
                .sorted(Comparator.comparing(EventoProgramado::instantUtc))
                .toList();
    }

    public EventoVueloDTO crearEventoVuelo(VueloAgrupado vuelo, TipoEvento tipoEvento, String estado) {
        EventoVueloDTO evento = new EventoVueloDTO();
        evento.setTipo(tipoEvento);
        evento.setFechaHoraEvento(LocalDateTime.now().toString());
        evento.setCodigoVuelo(vuelo.codigoVuelo());
        evento.setOrigenIata(vuelo.origenIata());
        evento.setDestinoIata(vuelo.destinoIata());
        evento.setEstado(estado);
        evento.setCantidadMaletas(vuelo.cantidadMaletas());

        // Mapeo completo de todos los campos de fecha/hora para evitar 'undefined' en el frontend
        evento.setHoraSalida(vuelo.fechaHoraSalida().toString());
        evento.setHoraLlegada(vuelo.fechaHoraLlegada().toString());
        evento.setHoraSalidaLocal(vuelo.fechaHoraSalida().toString());
        evento.setHoraLlegadaLocal(vuelo.fechaHoraLlegada().toString());
        evento.setHoraSalidaUtc(vuelo.fechaHoraSalidaUtc().toString());
        evento.setHoraLlegadaUtc(vuelo.fechaHoraLlegadaUtc().toString());

        return evento;
    }

    public EventoAeropuertoDTO crearEventoAeropuerto(Aeropuerto aeropuerto, int maletasActuales) {
        int capacidad = aeropuerto.getCapacidadAlmacen();
        double porcentaje = capacidad > 0 ? (maletasActuales * 100.0) / capacidad : 0.0;

        String estadoSemaforo = "VERDE";
        if (porcentaje >= 90.0) estadoSemaforo = "ROJO";
        else if (porcentaje >= 70.0) estadoSemaforo = "AMARILLO";

        EventoAeropuertoDTO evento = new EventoAeropuertoDTO();
        evento.setTipo(TipoEvento.AEROPUERTO_ACTUALIZADO);
        evento.setFechaHoraEvento(LocalDateTime.now().toString());
        evento.setCodigoAeropuerto(aeropuerto.getCodigoIata());
        evento.setMaletasActuales(maletasActuales);
        evento.setCapacidadAlmacen(capacidad);
        evento.setPorcentajeOcupacion(porcentaje);
        evento.setEstado(estadoSemaforo);
        return evento;
    }

    public MetricasColapsoDTO calcularMetricasColapso(
            int ciclo, LocalDate ventanaInicio, LocalDate ventanaFin,
            List<Envio> nuevos, List<Envio> pendientes, List<Envio> enviosAProcesar,
            SolucionRuta solucion, Map<String, Aeropuerto> mapaAeropuertos
    ) {
        int enviosProcesados = enviosAProcesar.size();
        int maletasProcesadas = nuevos.stream().mapToInt(Envio::getCantidadMaletas).sum()
                + pendientes.stream().mapToInt(Envio::getCantidadMaletas).sum();

        int enviosSinItinerario = solucion.getSinItinerarioCount();
        int slaIncumplidos = solucion.getExcedeSlaCount();

        // Calculamos vuelos sobrecargados
        Map<VueloInstanciado, Integer> cargaPorVuelo = new LinkedHashMap<>();
        for (RutaAsignada asig : solucion.getAsignaciones()) {
            if (asig.getItinerario() == null) continue;
            for (VueloInstanciado v : asig.getItinerario().getVuelos()) {
                cargaPorVuelo.merge(v, asig.getEnvio().getCantidadMaletas(), Integer::sum);
            }
        }
        int vuelosSobrecargados = (int) cargaPorVuelo.entrySet().stream()
                .filter(entry -> entry.getValue() > entry.getKey().getCapacidadMax()).count();

        return new MetricasColapsoDTO(
                ciclo, ventanaInicio.toString(), ventanaFin.toString(),
                nuevos.size(), nuevos.stream().mapToInt(Envio::getCantidadMaletas).sum(),
                pendientes.size(), pendientes.stream().mapToInt(Envio::getCantidadMaletas).sum(),
                enviosProcesados, maletasProcesadas, enviosSinItinerario, 0,
                enviosProcesados > 0 ? enviosSinItinerario / (double) enviosProcesados : 0.0,
                slaIncumplidos,
                enviosProcesados > 0 ? slaIncumplidos / (double) enviosProcesados : 0.0,
                vuelosSobrecargados, 0, 0.0, solucion.getFitness(), null
        );
    }

    public List<String> detectarCriteriosColapso(MetricasColapsoDTO metricas) {
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

        return criterios;
    }

    // ================== RECORDS INTERNOS DE APOYO ==================

    public record VueloAgrupado(
            Long codigoVuelo, String origenIata, String destinoIata,
            LocalDateTime fechaHoraSalida, LocalDateTime fechaHoraLlegada,
            Instant fechaHoraSalidaUtc, Instant fechaHoraLlegadaUtc, int cantidadMaletas
    ) {}

    public record EventoProgramado(TipoEvento tipo, Instant instantUtc, VueloAgrupado vuelo) {}

    private static class VueloAgrupadoAcumulado {
        private final VueloInstanciado vuelo;
        private int cantidadMaletas;
        public VueloAgrupadoAcumulado(VueloInstanciado vuelo) { this.vuelo = vuelo; }
        public void sumar(int cantidad) { cantidadMaletas += cantidad; }
        public VueloAgrupado toVueloAgrupado() {
            return new VueloAgrupado(
                    vuelo.getCodigoBase(), vuelo.getOrigenIata(), vuelo.getDestinoIata(),
                    vuelo.getFechaHoraSalida(), vuelo.getFechaHoraLlegada(),
                    vuelo.getFechaHoraSalidaUtc(), vuelo.getFechaHoraLlegadaUtc(), cantidadMaletas
            );
        }
    }
}