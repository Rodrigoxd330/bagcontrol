package pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion;

import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class FitnessEvaluator {

    private static final double PENALIZACION_EXTREMA = 999999.0;

    public double evaluar(SolucionRuta solucion, List<Aeropuerto> aeropuertos, List<Vuelo> vuelos) {
        double fitness = 0.0;

        Map<Long, Integer> cargaPorVuelo = new HashMap<>();
        Map<String, Integer> cargaPorAeropuerto = new HashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getVuelo() == null) {
                fitness += PENALIZACION_EXTREMA;
                continue;
            }

            Vuelo vuelo = asignacion.getVuelo();
            var envio = asignacion.getEnvio();

            // Base del costo: duración del vuelo en horas
            double duracion = PlanificadorUtils.calcularDuracionVueloHoras(vuelo);
            fitness += duracion;

            // Penalización si excede plazo máximo
            if (PlanificadorUtils.excedePlazoMaximo(envio, vuelo, aeropuertos)) {
                fitness += PENALIZACION_EXTREMA;
            }

            // Acumular carga por vuelo
            cargaPorVuelo.merge(vuelo.getCodigo(), envio.getCantidadMaletas(), Integer::sum);

            // Acumular carga por aeropuerto de destino
            cargaPorAeropuerto.merge(vuelo.getDestinoIata(), envio.getCantidadMaletas(), Integer::sum);
        }

        // Penalizar vuelos sobrecargados
        for (Vuelo vuelo : vuelos) {
            int carga = cargaPorVuelo.getOrDefault(vuelo.getCodigo(), 0);
            if (carga > vuelo.getCapacidadMax()) {
                fitness += PENALIZACION_EXTREMA + (carga - vuelo.getCapacidadMax()) * 1000.0;
            }
        }

        // Penalizar almacenes saturados
        Map<String, Aeropuerto> mapaAeropuertos = new HashMap<>();
        for (Aeropuerto aeropuerto : aeropuertos) {
            mapaAeropuertos.put(aeropuerto.getCodigoIata(), aeropuerto);
        }

        for (Map.Entry<String, Integer> entry : cargaPorAeropuerto.entrySet()) {
            Aeropuerto aeropuerto = mapaAeropuertos.get(entry.getKey());
            if (aeropuerto != null && entry.getValue() > aeropuerto.getCapacidadAlmacen()) {
                fitness += PENALIZACION_EXTREMA + (entry.getValue() - aeropuerto.getCapacidadAlmacen()) * 1000.0;
            }
        }

        solucion.setFitness(fitness);
        return fitness;
    }
}