package pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion;

import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.HashMap;
import java.util.Map;

@Component
public class FitnessEvaluator {

    private static final double PENALIZACION_EXTREMA = 999999.0;

    public double evaluar(SolucionRuta solucion, Map<String, Aeropuerto> mapaAeropuertos) {
        double fitness = 0.0;

        // 🔥 Usamos el objeto VueloInstanciado directamente como llave para tener su capacidad a la mano
        Map<VueloInstanciado, Integer> cargaPorVuelo = new HashMap<>();
        Map<String, Integer> cargaPorAeropuerto = new HashMap<>();

        // ================= RECORRER ASIGNACIONES =================
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {

            if (asignacion.getVuelo() == null) {
                fitness += PENALIZACION_EXTREMA;
                continue;
            }

            VueloInstanciado vuelo = asignacion.getVuelo();
            var envio = asignacion.getEnvio();

            // Duración del vuelo
            fitness += PlanificadorUtils.calcularDuracionVueloHoras(vuelo);

            // Verificación SLA ultra rápida usando el mapa ya procesado
            if (PlanificadorUtils.excedePlazoMaximo(envio, vuelo, mapaAeropuertos)) {
                fitness += PENALIZACION_EXTREMA;
            }

            // Acumular cargas
            cargaPorVuelo.merge(vuelo, envio.getCantidadMaletas(), Integer::sum);
            cargaPorAeropuerto.merge(vuelo.getDestinoIata(), envio.getCantidadMaletas(), Integer::sum);
        }

        // ================= CAPACIDAD DE VUELOS (Solo los usados) =================
        for (Map.Entry<VueloInstanciado, Integer> entry : cargaPorVuelo.entrySet()) {
            VueloInstanciado vuelo = entry.getKey();
            int cargaActual = entry.getValue();

            if (cargaActual > vuelo.getCapacidadMax()) {
                fitness += PENALIZACION_EXTREMA + (cargaActual - vuelo.getCapacidadMax()) * 1000.0;
            }
        }

        // ================= CAPACIDAD DE AEROPUERTOS =================
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