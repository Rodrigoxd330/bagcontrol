package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.metricas.MetricasPlanificacionBloque;
import pe.edu.pucp.inf.bagcontrol.planificacion.metricas.PlanificacionInstrumentacion;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class ItinerarioService {

    private static final int MIN_CONEXION_MINUTOS = 30;
    private static final int MAX_ESPERA_ESCALA_HORAS = 12;
    private static final int MAX_ITINERARIOS_POR_RUTA_POR_DIA = 200;
    private static final int MAX_ITINERARIOS_POR_RUTA_MIN = 500;
    private static final double PROPORCION_ESCALAS_EN_RECORTE = 0.40;

    public Map<String, List<Itinerario>> generarItinerariosPorRuta(List<VueloInstanciado> vuelos) {
        long inicio = System.currentTimeMillis();
        Map<String, List<VueloInstanciado>> vuelosPorOrigen = new HashMap<>();
        Map<String, List<Itinerario>> itinerariosPorRuta = new HashMap<>();
        int itinerariosDirectos = 0;
        int itinerariosConEscala = 0;
        int rutasRecortadas = 0;
        int itinerariosEliminadosPorRecorte = 0;

        for (VueloInstanciado vuelo : vuelos) {
            if (vuelo.isEstaCancelado()) continue;

            vuelosPorOrigen
                    .computeIfAbsent(vuelo.getOrigenIata(), k -> new ArrayList<>())
                    .add(vuelo);

            String keyDirecto = vuelo.getOrigenIata() + "-" + vuelo.getDestinoIata();
            itinerariosPorRuta
                    .computeIfAbsent(keyDirecto, k -> new ArrayList<>())
                    .add(new Itinerario(List.of(vuelo)));
            itinerariosDirectos++;
        }

        long inicioGeneracionEscalas = System.currentTimeMillis();
        for (VueloInstanciado primerVuelo : vuelos) {
            if (primerVuelo.isEstaCancelado()) continue;

            List<VueloInstanciado> segundosVuelos =
                    vuelosPorOrigen.getOrDefault(primerVuelo.getDestinoIata(), Collections.emptyList());

            for (VueloInstanciado segundoVuelo : segundosVuelos) {
                if (segundoVuelo.isEstaCancelado()) continue;

                if (primerVuelo.getOrigenIata().equalsIgnoreCase(segundoVuelo.getDestinoIata())) {
                    continue;
                }

                if (!conexionValida(primerVuelo, segundoVuelo)) {
                    continue;
                }

                String keyConEscala = primerVuelo.getOrigenIata() + "-" + segundoVuelo.getDestinoIata();

                itinerariosPorRuta
                        .computeIfAbsent(keyConEscala, k -> new ArrayList<>())
                        .add(new Itinerario(List.of(primerVuelo, segundoVuelo)));
                itinerariosConEscala++;
            }
        }
        long tiempoGeneracionEscalasMs = System.currentTimeMillis() - inicioGeneracionEscalas;

        int totalAntesRecorte = itinerariosPorRuta.values().stream().mapToInt(List::size).sum();
        int diasInstanciados = Math.max(1, (int) vuelos.stream()
                .map(v -> v.getFechaHoraSalida().toLocalDate())
                .distinct()
                .count());
        int maxItinerariosPorRuta = Math.max(
                MAX_ITINERARIOS_POR_RUTA_MIN,
                diasInstanciados * MAX_ITINERARIOS_POR_RUTA_POR_DIA
        );

        for (Map.Entry<String, List<Itinerario>> entry : itinerariosPorRuta.entrySet()) {
            List<Itinerario> lista = entry.getValue();
            lista.sort(Comparator
                    .comparing(Itinerario::getFechaHoraSalidaUtc)
                    .thenComparing(Itinerario::getFechaHoraLlegadaUtc)
                    .thenComparingInt(Itinerario::getCantidadVuelos));

            if (lista.size() > maxItinerariosPorRuta) {
                rutasRecortadas++;
                itinerariosEliminadosPorRecorte += lista.size() - maxItinerariosPorRuta;
                entry.setValue(recortarConDiversidadTipoYTemporal(lista, maxItinerariosPorRuta));
            }
        }

        MetricasPlanificacionBloque metricas = PlanificacionInstrumentacion.actual();
        if (metricas != null) {
            metricas.sumarGeneracionItinerariosMs(System.currentTimeMillis() - inicio);
            int directosRetenidos = itinerariosPorRuta.values().stream()
                    .flatMap(Collection::stream)
                    .mapToInt(itinerario -> itinerario.getCantidadVuelos() == 1 ? 1 : 0)
                    .sum();
            int escalasRetenidas = itinerariosPorRuta.values().stream()
                    .flatMap(Collection::stream)
                    .mapToInt(itinerario -> itinerario.getCantidadVuelos() > 1 ? 1 : 0)
                    .sum();
            metricas.sumarCandidatos(directosRetenidos, escalasRetenidas);
            metricas.registrarRecorteCandidatos(
                    itinerariosDirectos, itinerariosConEscala, directosRetenidos, escalasRetenidas);
            metricas.sumarGeneracionEscalasMs(tiempoGeneracionEscalasMs);
        }
        return itinerariosPorRuta;
    }

    private List<Itinerario> recortarConDiversidadTipoYTemporal(List<Itinerario> itinerarios, int limite) {
        List<Itinerario> directos = itinerarios.stream()
                .filter(itinerario -> itinerario.getCantidadVuelos() == 1)
                .toList();
        List<Itinerario> conEscala = itinerarios.stream()
                .filter(itinerario -> itinerario.getCantidadVuelos() == 2)
                .toList();

        int cupoEscalas = Math.min(
                conEscala.size(),
                (int) Math.ceil(limite * PROPORCION_ESCALAS_EN_RECORTE)
        );
        int cupoDirectos = Math.min(directos.size(), limite - cupoEscalas);
        List<Itinerario> seleccionados = new ArrayList<>(limite);
        seleccionados.addAll(recortarConDiversidadTemporal(directos, cupoDirectos));
        seleccionados.addAll(recortarConDiversidadTemporal(conEscala, cupoEscalas));

        if (seleccionados.size() < limite) {
            Set<Itinerario> incluidos = new HashSet<>(seleccionados);
            List<Itinerario> restantes = itinerarios.stream()
                    .filter(itinerario -> !incluidos.contains(itinerario))
                    .toList();
            seleccionados.addAll(recortarConDiversidadTemporal(restantes, limite - seleccionados.size()));
        }
        seleccionados.sort(Comparator
                .comparing(Itinerario::getFechaHoraSalidaUtc)
                .thenComparing(Itinerario::getFechaHoraLlegadaUtc)
                .thenComparingInt(Itinerario::getCantidadVuelos));
        return seleccionados;
    }

    private List<Itinerario> recortarConDiversidadTemporal(List<Itinerario> itinerarios, int limite) {
        if (limite <= 0 || itinerarios.isEmpty()) {
            return List.of();
        }
        Map<Instant, List<Itinerario>> porHoraSalida = new TreeMap<>();
        for (Itinerario itinerario : itinerarios) {
            Instant hora = itinerario.getFechaHoraSalidaUtc().truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            porHoraSalida.computeIfAbsent(hora, ignorado -> new ArrayList<>()).add(itinerario);
        }
        porHoraSalida.values().forEach(lista -> lista.sort(Comparator
                .comparing(Itinerario::getFechaHoraLlegadaUtc)
                .thenComparingInt(Itinerario::getCantidadVuelos)
                .thenComparingDouble(PlanificadorUtils::calcularDuracionItinerarioHoras)));

        List<Itinerario> seleccionados = new ArrayList<>(limite);
        for (int ronda = 0; seleccionados.size() < limite; ronda++) {
            boolean agrego = false;
            for (List<Itinerario> hora : porHoraSalida.values()) {
                if (ronda < hora.size()) {
                    seleccionados.add(hora.get(ronda));
                    agrego = true;
                    if (seleccionados.size() == limite) {
                        break;
                    }
                }
            }
            if (!agrego) {
                break;
            }
        }
        seleccionados.sort(Comparator
                .comparing(Itinerario::getFechaHoraSalidaUtc)
                .thenComparing(Itinerario::getFechaHoraLlegadaUtc));
        return seleccionados;
    }

    private boolean conexionValida(VueloInstanciado primero, VueloInstanciado segundo) {
        Instant llegadaPrimero = primero.getFechaHoraLlegadaUtc();
        Instant salidaSegundo = segundo.getFechaHoraSalidaUtc();

        if (salidaSegundo.isBefore(llegadaPrimero.plus(Duration.ofMinutes(MIN_CONEXION_MINUTOS)))) {
            return false;
        }

        long esperaHoras = Duration.between(llegadaPrimero, salidaSegundo).toHours();

        return esperaHoras <= MAX_ESPERA_ESCALA_HORAS;
    }
}
