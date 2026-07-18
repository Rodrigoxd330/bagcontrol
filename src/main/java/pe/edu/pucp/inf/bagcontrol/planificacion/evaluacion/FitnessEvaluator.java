package pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion;

import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

@Component
public class FitnessEvaluator {

    // Penalizaciones robustas para forzar el cumplimiento de reglas
    private static final double PENALIZACION_SIN_ITINERARIO = 1_000_000.0;
    private static final double PENALIZACION_EXCEDE_SLA = 1_000_000.0;
    private static final double PENALIZACION_SOBRECARGA_VUELO = 50.0;
    private static final double PENALIZACION_SOBRECARGA_AEROPUERTO = 1000000.0;
    private static final double PENALIZACION_ESCALA = 5.0;
    private static final double PENALIZACION_VUELO_CANCELADO = 100000.0;

    public double evaluar(SolucionRuta solucion, Map<String, Aeropuerto> mapaAeropuertos) {
        return evaluar(solucion, mapaAeropuertos, Map.of());
    }

    public double evaluar(
            SolucionRuta solucion,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial
    ) {
        return evaluar(solucion, mapaAeropuertos, inventarioInicial, Set.of());
    }

    public double evaluar(
            SolucionRuta solucion,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos
    ) {
        double fitness = 0.0;
        int sinItinerario = 0;
        int excedeSla = 0;
        int vuelosCanceladosUsados = 0;

        Map<VueloInstanciado, Integer> cargaPorVuelo = new HashMap<>();
        Map<String, NavigableMap<java.time.Instant, Integer>> movimientosPorAeropuerto = new HashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            Itinerario itinerario = asignacion.getItinerario();
            var envio = asignacion.getEnvio();
            if (enviosNuevos.contains(envio.getIdPedido())) {
                registrarMovimientoAeropuerto(
                        movimientosPorAeropuerto,
                        envio.getOrigenIata(),
                        PlanificadorUtils.obtenerFechaIngresoUtc(envio),
                        envio.getCantidadMaletas()
                );
            }

            if (itinerario == null) {
                sinItinerario++;
                Aeropuerto origen = mapaAeropuertos.get(envio.getOrigenIata());
                double presionOrigen = origen == null || origen.getCapacidadAlmacen() <= 0
                        ? 0.0
                        : inventarioInicial.getOrDefault(envio.getOrigenIata(), 0)
                                / (double) origen.getCapacidadAlmacen();
                fitness += PENALIZACION_SIN_ITINERARIO
                        + envio.getCantidadMaletas() * 10_000.0
                        + presionOrigen * 500_000.0;
                asignacion.setExcedeSla(false); // No tiene itinerario, el SLA terrestre lo evalúa el motor
                continue;
            }

            double horasHastaEntrega = java.time.Duration.between(
                    PlanificadorUtils.obtenerFechaIngresoUtc(envio), itinerario.getFechaHoraLlegadaUtc()
            ).toMinutes() / 60.0;
            fitness += Math.max(horasHastaEntrega, 0.0) * 20.0;

            if (itinerario.getCantidadVuelos() > 1) {
                fitness += (itinerario.getCantidadVuelos() - 1) * PENALIZACION_ESCALA;
            }

            if (itinerario.contieneVueloCancelado()) {
                vuelosCanceladosUsados++;
                fitness += PENALIZACION_VUELO_CANCELADO;
            }

            // Contador de incumplimiento de SLA en vuelo
            if (PlanificadorUtils.excedePlazoMaximo(envio, itinerario, mapaAeropuertos)) {
                excedeSla++;
                fitness += PENALIZACION_EXCEDE_SLA;
                asignacion.setExcedeSla(true);
            } else {
                asignacion.setExcedeSla(false);
            }

            for (int i = 0; i < itinerario.getVuelos().size(); i++) {
                VueloInstanciado vuelo = itinerario.getVuelos().get(i);
                cargaPorVuelo.compute(
                        vuelo,
                        (ignorado, actual) -> (actual == null ? vuelo.getOcupacionActual() : actual)
                                + envio.getCantidadMaletas()
                );
                registrarMovimientoAeropuerto(
                        movimientosPorAeropuerto,
                        vuelo.getOrigenIata(),
                        vuelo.getFechaHoraSalidaUtc(),
                        -envio.getCantidadMaletas()
                );
                if (i < itinerario.getVuelos().size() - 1) {
                    registrarMovimientoAeropuerto(
                            movimientosPorAeropuerto,
                            vuelo.getDestinoIata(),
                            vuelo.getFechaHoraLlegadaUtc(),
                            envio.getCantidadMaletas()
                    );
                }
            }
        }

        // Penalizaciones por capacidad de vuelo
        int vuelosSobrecargados = 0;
        for (Map.Entry<VueloInstanciado, Integer> entry : cargaPorVuelo.entrySet()) {
            VueloInstanciado vuelo = entry.getKey();
            int cargaActual = entry.getValue();
            int capacidadMax = vuelo.getCapacidadMax();
            if (cargaActual > capacidadMax) {
                vuelosSobrecargados++;
                int exceso = cargaActual - capacidadMax;
                fitness += Math.pow(exceso, 2) * PENALIZACION_SOBRECARGA_VUELO;
            } else if (capacidadMax > 0) {
                fitness += calcularPenalizacionSaturacionVuelo(cargaActual / (double) capacidadMax);
            }
        }

        // Penalizaciones por capacidad de aeropuerto
        int aeropuertosSaturados = 0;
        Map<String, Integer> ocupacionMaximaPorAeropuerto = calcularOcupacionMaximaPorAeropuerto(
                movimientosPorAeropuerto, inventarioInicial
        );
        for (Map.Entry<String, Integer> entry : ocupacionMaximaPorAeropuerto.entrySet()) {
            Aeropuerto aeropuerto = mapaAeropuertos.get(entry.getKey());
            if (aeropuerto == null || aeropuerto.getCapacidadAlmacen() <= 0) {
                continue;
            }
            int capacidad = aeropuerto.getCapacidadAlmacen();
            int ocupacionMaxima = entry.getValue();
            fitness += calcularPenalizacionOcupacionAeropuerto(ocupacionMaxima, capacidad);
            if (ocupacionMaxima > capacidad) {
                aeropuertosSaturados++;
                int exceso = ocupacionMaxima - capacidad;
                fitness += exceso * PENALIZACION_SOBRECARGA_AEROPUERTO;
            }
        }

        // Actualizar métricas en el objeto solución
        solucion.setFitness(fitness);
        solucion.setSinItinerarioCount(sinItinerario);
        solucion.setExcedeSlaCount(excedeSla);
        solucion.setVuelosCanceladosUsadosCount(vuelosCanceladosUsados);
        solucion.setVuelosSobrecargadosCount(vuelosSobrecargados);
        solucion.setAeropuertosSaturadosCount(aeropuertosSaturados);

        return fitness;
    }

    private double calcularPenalizacionSaturacionVuelo(double ocupacion) {
        if (ocupacion > 0.95) return 10_000.0 + (ocupacion - 0.95) * 100_000.0;
        if (ocupacion > 0.90) return 1_000.0 + (ocupacion - 0.90) * 10_000.0;
        if (ocupacion > 0.80) return 100.0 + (ocupacion - 0.80) * 1_000.0;
        return 0.0;
    }

    private void registrarMovimientoAeropuerto(
            Map<String, NavigableMap<java.time.Instant, Integer>> movimientosPorAeropuerto,
            String codigoIata,
            java.time.Instant instante,
            int variacion
    ) {
        movimientosPorAeropuerto
                .computeIfAbsent(codigoIata, key -> new TreeMap<>())
                .merge(instante, variacion, Integer::sum);
    }

    private Map<String, Integer> calcularOcupacionMaximaPorAeropuerto(
            Map<String, NavigableMap<java.time.Instant, Integer>> movimientosPorAeropuerto,
            Map<String, Integer> inventarioInicial
    ) {
        Map<String, Integer> ocupacionMaxima = new HashMap<>();
        inventarioInicial.forEach((codigoIata, inventario) -> ocupacionMaxima.put(codigoIata, Math.max(inventario, 0)));
        for (Map.Entry<String, NavigableMap<java.time.Instant, Integer>> entry : movimientosPorAeropuerto.entrySet()) {
            String codigoIata = entry.getKey();
            int ocupacion = inventarioInicial.getOrDefault(codigoIata, 0);
            int maximo = Math.max(ocupacion, 0);
            for (int variacion : entry.getValue().values()) {
                ocupacion += variacion;
                maximo = Math.max(maximo, ocupacion);
            }
            ocupacionMaxima.merge(codigoIata, maximo, Math::max);
        }
        return ocupacionMaxima;
    }

    private double calcularPenalizacionOcupacionAeropuerto(int ocupacion, int capacidad) {
        double penalizacion = 0.0;
        penalizacion += penalizarTramo(ocupacion, capacidad, 0.70, 0.85, 50.0);
        penalizacion += penalizarTramo(ocupacion, capacidad, 0.85, 0.95, 500.0);
        penalizacion += penalizarTramo(ocupacion, capacidad, 0.95, Double.POSITIVE_INFINITY, 5000.0);
        return penalizacion;
    }

    private double penalizarTramo(
            int ocupacion,
            int capacidad,
            double desdeRatio,
            double hastaRatio,
            double factor
    ) {
        double desde = capacidad * desdeRatio;
        double hasta = Double.isInfinite(hastaRatio) ? ocupacion : capacidad * hastaRatio;
        double deficit = Math.max(0.0, Math.min(ocupacion, hasta) - desde);
        return deficit * factor;
    }
}
