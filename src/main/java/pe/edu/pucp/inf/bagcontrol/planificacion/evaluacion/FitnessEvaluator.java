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

@Component
public class FitnessEvaluator {

    // Penalizaciones robustas para forzar el cumplimiento de reglas
    private static final double PENALIZACION_SIN_ITINERARIO = 10000.0;
    private static final double PENALIZACION_EXCEDE_SLA = 5000.0;
    private static final double PENALIZACION_SOBRECARGA_VUELO = 50.0;
    private static final double PENALIZACION_SOBRECARGA_AEROPUERTO = 10.0;
    private static final double PENALIZACION_ESCALA = 10.0;

    public double evaluar(SolucionRuta solucion, Map<String, Aeropuerto> mapaAeropuertos) {
        double fitness = 0.0;
        int sinItinerario = 0;
        int excedeSla = 0;

        Map<VueloInstanciado, Integer> cargaPorVuelo = new HashMap<>();
        Map<String, Integer> cargaPorAeropuerto = new HashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            Itinerario itinerario = asignacion.getItinerario();

            if (itinerario == null) {
                sinItinerario++;
                fitness += PENALIZACION_SIN_ITINERARIO;
                continue;
            }

            var envio = asignacion.getEnvio();
            fitness += PlanificadorUtils.calcularDuracionItinerarioHoras(itinerario);

            if (itinerario.getCantidadVuelos() > 1) {
                fitness += (itinerario.getCantidadVuelos() - 1) * PENALIZACION_ESCALA;
            }

            // Contador de incumplimiento de SLA
            if (PlanificadorUtils.excedePlazoMaximo(envio, itinerario, mapaAeropuertos)) {
                excedeSla++;
                fitness += PENALIZACION_EXCEDE_SLA;
            }

            for (VueloInstanciado vuelo : itinerario.getVuelos()) {
                cargaPorVuelo.merge(vuelo, envio.getCantidadMaletas(), Integer::sum);
            }

            cargaPorAeropuerto.merge(itinerario.getDestinoIata(), envio.getCantidadMaletas(), Integer::sum);
        }

        // Penalizaciones por capacidad de vuelo
        for (Map.Entry<VueloInstanciado, Integer> entry : cargaPorVuelo.entrySet()) {
            VueloInstanciado vuelo = entry.getKey();
            int cargaActual = entry.getValue();
            if (cargaActual > vuelo.getCapacidadMax()) {
                int exceso = cargaActual - vuelo.getCapacidadMax();
                fitness += Math.pow(exceso, 2) * PENALIZACION_SOBRECARGA_VUELO;
            }
        }

        // Penalizaciones por capacidad de aeropuerto
        for (Map.Entry<String, Integer> entry : cargaPorAeropuerto.entrySet()) {
            Aeropuerto aeropuerto = mapaAeropuertos.get(entry.getKey());
            if (aeropuerto != null && entry.getValue() > aeropuerto.getCapacidadAlmacen()) {
                int exceso = entry.getValue() - aeropuerto.getCapacidadAlmacen();
                fitness += exceso * PENALIZACION_SOBRECARGA_AEROPUERTO;
            }
        }

        // Actualizar métricas en el objeto solución
        solucion.setFitness(fitness);
        solucion.setSinItinerarioCount(sinItinerario);
        solucion.setExcedeSlaCount(excedeSla);

        return fitness;
    }
}