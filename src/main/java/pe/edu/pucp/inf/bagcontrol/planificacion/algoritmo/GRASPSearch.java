package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class GRASPSearch {
    //Parametros puestos como variables globales para mayor conveniencia
    private static int Iteraciones = 30;
    private static int MaxVecinos = 50;
    private static double Alpha = 0.4;

    private final FitnessEvaluator fitnessEvaluator;
    private final Random random = new Random();

    public static void establecerParametros(int _iteraciones,int _maxVecinos,double _alpha){
        Iteraciones = _iteraciones;
        MaxVecinos = _maxVecinos;
        Alpha = _alpha;
    }

    public SolucionRuta ejecutar(List<Envio> envios, Map<String, List<Itinerario>> itinerariosPorRuta, List<Aeropuerto> aeropuertos) {
        return ejecutarConParametros(envios, itinerariosPorRuta, aeropuertos, Iteraciones, MaxVecinos, Alpha);
    }

    public SolucionRuta ejecutarConParametros(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            List<Aeropuerto> aeropuertos,
            int iteraciones,
            int maxVecinos,
            double alpha
    ) {
        SolucionRuta mejorSolucion = null;

        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

        for (int i = 0; i < iteraciones; i++) {
            SolucionRuta solucion = construirSolucion(envios, itinerariosPorRuta, mapaAeropuertos, alpha);
            solucion = busquedaLocal(solucion, itinerariosPorRuta, mapaAeropuertos, maxVecinos);

            fitnessEvaluator.evaluar(solucion, mapaAeropuertos);

            if (mejorSolucion == null || solucion.getFitness() < mejorSolucion.getFitness()) {
                mejorSolucion = solucion.clonar();
            }
        }
        return mejorSolucion;
    }

    private SolucionRuta construirSolucion(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos,
            double alpha
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
                int limite = (int) Math.ceil(alpha * posibles.size());
                limite = Math.max(limite, 1);

                List<Itinerario> rcl = posibles.subList(0, Math.min(limite, posibles.size()));
                Itinerario elegido = rcl.get(random.nextInt(rcl.size()));

                PlanificadorUtils.acumularCargaItinerario(elegido, envio, cargaAcumulada);
                solucion.agregarAsignacion(envio, elegido);
            }
        }

        return solucion;
    }

    private SolucionRuta busquedaLocal(
            SolucionRuta solucion,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos,
            int maxVecinos
    ) {
        SolucionRuta mejor = solucion.clonar();
        double mejorFitness = fitnessEvaluator.evaluar(mejor, mapaAeropuertos);

        boolean mejora = true;
        int maxIterLocal = 50;
        int iter = 0;

        while (mejora && iter < maxIterLocal) {
            iter++;
            mejora = false;

            var vecinos = PlanificadorUtils.generarVecindario(
                    mejor,
                    itinerariosPorRuta,
                    mapaAeropuertos,
                    maxVecinos
            );

            for (var movimiento : vecinos) {
                mejor.aplicarMovimientoDefinitivo(movimiento);
                double fitnessCandidato = fitnessEvaluator.evaluar(mejor, mapaAeropuertos);

                if (fitnessCandidato < mejorFitness) {
                    mejorFitness = fitnessCandidato;
                    mejora = true;
                    break;
                } else {
                    mejor.deshacerMovimiento(movimiento);
                    mejor.setFitness(mejorFitness);
                }
            }
        }
        return mejor;
    }
}