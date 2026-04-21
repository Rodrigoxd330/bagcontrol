package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
@RequiredArgsConstructor
public class GRASPSearch {

    private final FitnessEvaluator fitnessEvaluator;
    private final Random random = new Random();

    public SolucionRuta ejecutar(List<Envio> envios, List<Vuelo> vuelos, List<Aeropuerto> aeropuertos) {

        int iteraciones = 3; // puedes aumentar luego
        SolucionRuta mejorSolucion = null;

        for (int i = 0; i < iteraciones; i++) {
            System.out.println("Iteración GRASP: " + (i + 1));
            // 1. Fase constructiva
            SolucionRuta solucion = construirSolucion(envios, vuelos);

            // 2. Búsqueda local
            solucion = busquedaLocal(solucion, vuelos, aeropuertos);

            // 3. Evaluar
            fitnessEvaluator.evaluar(solucion, aeropuertos, vuelos);

            // 4. Guardar mejor
            if (mejorSolucion == null || solucion.getFitness() < mejorSolucion.getFitness()) {
                mejorSolucion = solucion;
            }
        }

        return mejorSolucion;
    }

    private SolucionRuta construirSolucion(List<Envio> envios, List<Vuelo> vuelos) {
        SolucionRuta solucion = new SolucionRuta();

        for (Envio envio : envios) {
            List<Vuelo> posibles = PlanificadorUtils.buscarVuelosPosiblesParaEnvio(envio, vuelos);

            if (posibles.isEmpty()) {
                solucion.agregarAsignacion(envio, null);
            } else {
                // selección aleatoria (GRASP básico)
                Vuelo elegido = posibles.get(random.nextInt(posibles.size()));
                solucion.agregarAsignacion(envio, elegido);
            }
        }

        return solucion;
    }

    private SolucionRuta busquedaLocal(SolucionRuta solucion, List<Vuelo> vuelos, List<Aeropuerto> aeropuertos) {

        SolucionRuta mejor = solucion.clonar();
        fitnessEvaluator.evaluar(mejor, aeropuertos, vuelos);

        boolean mejora = true;

        while (mejora) {
            mejora = false;

            var vecinos = PlanificadorUtils.generarVecindario(mejor, vuelos);

            for (var movimiento : vecinos) {

                SolucionRuta candidata = mejor.clonar();
                candidata.aplicarMovimientoDefinitivo(movimiento);

                fitnessEvaluator.evaluar(candidata, aeropuertos, vuelos);

                if (candidata.getFitness() < mejor.getFitness()) {
                    mejor = candidata;
                    mejora = true;
                    break;
                }
            }
        }

        return mejor;
    }
}