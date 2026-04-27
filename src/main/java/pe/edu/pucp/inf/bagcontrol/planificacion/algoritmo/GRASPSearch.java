package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class GRASPSearch {

    private final FitnessEvaluator fitnessEvaluator;
    private final Random random = new Random();

    public SolucionRuta ejecutar(List<Envio> envios, Map<String, List<VueloInstanciado>> vuelosPorRuta, List<Aeropuerto> aeropuertos) {
        return ejecutarConParametros(envios, vuelosPorRuta, aeropuertos, 30, 50, 0.4);
    }

    public SolucionRuta ejecutarConParametros(
            List<Envio> envios,
            Map<String, List<VueloInstanciado>> vuelosPorRuta,
            List<Aeropuerto> aeropuertos,
            int iteraciones,
            int maxVecinos,
            double alpha
    ) {
        SolucionRuta mejorSolucion = null;

        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

        for (int i = 0; i < iteraciones; i++) {
            SolucionRuta solucion = construirSolucion(envios, vuelosPorRuta, alpha);
            solucion = busquedaLocal(solucion, vuelosPorRuta, mapaAeropuertos, maxVecinos);

            fitnessEvaluator.evaluar(solucion, mapaAeropuertos);

            if (mejorSolucion == null || solucion.getFitness() < mejorSolucion.getFitness()) {
                mejorSolucion = solucion;
            }
        }

        return mejorSolucion;
    }

    private SolucionRuta construirSolucion(List<Envio> envios, Map<String, List<VueloInstanciado>> vuelosPorRuta, double alpha) {
        SolucionRuta solucion = new SolucionRuta();

        for (Envio envio : envios) {
            String key = envio.getOrigenIata() + "-" + envio.getDestinoIata();
            List<VueloInstanciado> posibles = vuelosPorRuta.getOrDefault(key, Collections.emptyList());
            if (posibles.isEmpty()) {
                solucion.agregarAsignacion(envio, null);
            } else {
                int limite = (int) Math.ceil(alpha * posibles.size());
                limite = Math.max(limite, 1);
                List<VueloInstanciado> rcl = posibles.subList(0, limite);
                VueloInstanciado elegido = rcl.get(random.nextInt(rcl.size()));
                solucion.agregarAsignacion(envio, elegido);
            }
        }
        return solucion;
    }

    private SolucionRuta busquedaLocal(SolucionRuta solucion, Map<String, List<VueloInstanciado>> vuelosPorRuta, Map<String, Aeropuerto> mapaAeropuertos, int maxVecinos) {
        // Clonamos SOLO UNA VEZ al entrar a la búsqueda local
        SolucionRuta mejor = solucion.clonar();
        double mejorFitness = fitnessEvaluator.evaluar(mejor, mapaAeropuertos);

        boolean mejora = true;
        int maxIterLocal = 50;
        int iter = 0;

        while (mejora && iter < maxIterLocal) {
            iter++;
            mejora = false;

            var vecinos = PlanificadorUtils.generarVecindario(mejor, vuelosPorRuta, maxVecinos);

            for (var movimiento : vecinos) {
                // 🔥 PATRÓN APPLY & REVERT: En lugar de clonar toda la lista (111,000 objetos),
                // modificamos un solo puntero (1 objeto).

                mejor.aplicarMovimientoDefinitivo(movimiento);
                double fitnessCandidato = fitnessEvaluator.evaluar(mejor, mapaAeropuertos);

                if (fitnessCandidato < mejorFitness) {
                    mejorFitness = fitnessCandidato;
                    mejora = true;
                    break; // First-Improvement: Si mejora, dejamos el movimiento aplicado y cortamos.
                } else {
                    // Si no mejoró, deshacemos el movimiento y restauramos el fitness para probar el siguiente
                    mejor.deshacerMovimiento(movimiento);
                    mejor.setFitness(mejorFitness);
                }
            }
        }

        return mejor;
    }
}