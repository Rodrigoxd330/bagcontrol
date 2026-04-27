package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Movimiento;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TabuSearch {

    private final FitnessEvaluator fitnessEvaluator;

    public SolucionRuta ejecutar(List<Envio> envios, Map<String, List<VueloInstanciado>> vuelosPorRuta, List<Aeropuerto> aeropuertos) {
        return ejecutarConParametros(envios, vuelosPorRuta, aeropuertos, 120, 12, 50);
    }

    public SolucionRuta ejecutarConParametros(
            List<Envio> envios,
            Map<String, List<VueloInstanciado>> vuelosPorRuta,
            List<Aeropuerto> aeropuertos,
            int iteraciones,
            int tenure,
            int maxVecinos
    ) {
        Set<String> listaTabu = new HashSet<>();

        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

        SolucionRuta actual = generarSolucionInicial(envios, vuelosPorRuta);
        double actualFitness = fitnessEvaluator.evaluar(actual, mapaAeropuertos);

        SolucionRuta mejor = actual.clonar();
        double mejorFitnessGlobal = actualFitness;

        for (int i = 0; i < iteraciones; i++) {
            List<Movimiento> vecinos = PlanificadorUtils.generarVecindario(actual, vuelosPorRuta, maxVecinos);

            Movimiento mejorMovimiento = null;
            double mejorFitnessVecino = Double.MAX_VALUE;

            for (Movimiento mov : vecinos) {
                String id = mov.getIdMovimientoTabu();

                // 🔥 PATRÓN APPLY & REVERT (SIN CLONAR)
                actual.aplicarMovimientoDefinitivo(mov);
                double fitnessCandidato = fitnessEvaluator.evaluar(actual, mapaAeropuertos);

                boolean esMejorGlobal = fitnessCandidato < mejorFitnessGlobal;

                // Verificamos si es un movimiento válido (No Tabú, o Tabú pero mejor global)
                if (!listaTabu.contains(id) || esMejorGlobal) {
                    if (fitnessCandidato < mejorFitnessVecino) {
                        mejorFitnessVecino = fitnessCandidato;
                        mejorMovimiento = mov;
                    }
                }

                // Siempre deshacemos el movimiento en este bucle interno para probar el siguiente
                actual.deshacerMovimiento(mov);
                actual.setFitness(actualFitness);
            }

            if (mejorMovimiento == null) break;

            // Ahora aplicamos el MEJOR movimiento de forma definitiva a la solución 'actual'
            actual.aplicarMovimientoDefinitivo(mejorMovimiento);
            actualFitness = fitnessEvaluator.evaluar(actual, mapaAeropuertos);

            // Actualizamos la mejor solución global si aplica
            if (actualFitness < mejorFitnessGlobal) {
                mejor = actual.clonar(); // Aquí SÍ clonamos, porque es un nuevo hito global (ocurre raramente)
                mejorFitnessGlobal = actualFitness;
            }

            // Actualizar Lista Tabú
            listaTabu.add(mejorMovimiento.getIdMovimientoTabu());
            if (listaTabu.size() > tenure) {
                Iterator<String> it = listaTabu.iterator();
                it.next();
                it.remove();
            }
        }

        return mejor;
    }

    private SolucionRuta generarSolucionInicial(List<Envio> envios, Map<String, List<VueloInstanciado>> vuelosPorRuta) {
        SolucionRuta solucion = new SolucionRuta();

        for (Envio envio : envios) {
            String key = envio.getOrigenIata() + "-" + envio.getDestinoIata();
            List<VueloInstanciado> posibles = vuelosPorRuta.getOrDefault(key, Collections.emptyList());

            if (posibles.isEmpty()) {
                solucion.agregarAsignacion(envio, null);
            } else {
                solucion.agregarAsignacion(envio, posibles.get(0));
            }
        }

        return solucion;
    }
}