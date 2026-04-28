package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Movimiento;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TabuSearch {

    private final FitnessEvaluator fitnessEvaluator;

    public SolucionRuta ejecutar(List<Envio> envios, Map<String, List<Itinerario>> itinerariosPorRuta, List<Aeropuerto> aeropuertos) {
        return ejecutarConParametros(envios, itinerariosPorRuta, aeropuertos, 120, 12, 50);
    }

    public SolucionRuta ejecutarConParametros(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            List<Aeropuerto> aeropuertos,
            int iteraciones,
            int tenure,
            int maxVecinos
    ) {
        Set<String> listaTabu = new HashSet<>();

        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

        SolucionRuta actual = generarSolucionInicial(envios, itinerariosPorRuta, mapaAeropuertos);
        double actualFitness = fitnessEvaluator.evaluar(actual, mapaAeropuertos);

        SolucionRuta mejor = actual.clonar();
        double mejorFitnessGlobal = actualFitness;

        for (int i = 0; i < iteraciones; i++) {
            List<Movimiento> vecinos = PlanificadorUtils.generarVecindario(
                    actual,
                    itinerariosPorRuta,
                    mapaAeropuertos,
                    maxVecinos
            );

            Movimiento mejorMovimiento = null;
            double mejorFitnessVecino = Double.MAX_VALUE;

            for (Movimiento mov : vecinos) {
                String id = mov.getIdMovimientoTabu();

                actual.aplicarMovimientoDefinitivo(mov);
                double fitnessCandidato = fitnessEvaluator.evaluar(actual, mapaAeropuertos);

                boolean esMejorGlobal = fitnessCandidato < mejorFitnessGlobal;

                if (!listaTabu.contains(id) || esMejorGlobal) {
                    if (fitnessCandidato < mejorFitnessVecino) {
                        mejorFitnessVecino = fitnessCandidato;
                        mejorMovimiento = mov;
                    }
                }

                actual.deshacerMovimiento(mov);
                actual.setFitness(actualFitness);
            }

            if (mejorMovimiento == null) break;

            actual.aplicarMovimientoDefinitivo(mejorMovimiento);
            actualFitness = fitnessEvaluator.evaluar(actual, mapaAeropuertos);

            if (actualFitness < mejorFitnessGlobal) {
                mejor = actual.clonar();
                mejorFitnessGlobal = actualFitness;
            }

            listaTabu.add(mejorMovimiento.getIdMovimientoTabu());

            if (listaTabu.size() > tenure) {
                Iterator<String> it = listaTabu.iterator();
                it.next();
                it.remove();
            }
        }
        
        return mejor;
    }

    private SolucionRuta generarSolucionInicial(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos
    ) {
        SolucionRuta solucion = new SolucionRuta();
        Map<VueloInstanciado, Integer> cargaAcumulada = new HashMap<>();

        for (Envio envio : envios) {
            List<Itinerario> posibles = PlanificadorUtils.buscarItinerariosViablesParaEnvio(
                            envio,
                            itinerariosPorRuta,
                            mapaAeropuertos
                    ).stream()
                    .filter(i -> PlanificadorUtils.itinerarioTieneCapacidad(i, envio, cargaAcumulada))
                    .toList();

            if (posibles.isEmpty()) {
                solucion.agregarAsignacion(envio, null);
            } else {
                Itinerario elegido = posibles.get(0);
                PlanificadorUtils.acumularCargaItinerario(elegido, envio, cargaAcumulada);
                solucion.agregarAsignacion(envio, elegido);
            }
        }

        return solucion;
    }
}