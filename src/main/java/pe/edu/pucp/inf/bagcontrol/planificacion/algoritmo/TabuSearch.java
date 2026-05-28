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
    private final Random random = new Random();

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
        long inicio = System.currentTimeMillis();
        Set<String> listaTabu = new LinkedHashSet<>();
        int iteracionesEjecutadas = 0;
        int vecinosGenerados = 0;
        int vecinosEvaluados = 0;
        int movimientosAceptados = 0;
        int mejorasGlobales = 0;

        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

        SolucionRuta actual = generarSolucionInicial(envios, itinerariosPorRuta, mapaAeropuertos);
        double actualFitness = fitnessEvaluator.evaluar(actual, mapaAeropuertos);

        SolucionRuta mejor = actual.clonar();
        double mejorFitnessGlobal = actualFitness;

        for (int i = 0; i < iteraciones; i++) {
            iteracionesEjecutadas++;
            List<Movimiento> vecinos = PlanificadorUtils.generarVecindario(
                    actual,
                    itinerariosPorRuta,
                    mapaAeropuertos,
                    maxVecinos
            );
            vecinosGenerados += vecinos.size();

            Movimiento mejorMovimiento = null;
            double mejorFitnessVecino = Double.MAX_VALUE;

            for (Movimiento mov : vecinos) {
                vecinosEvaluados++;
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
            movimientosAceptados++;
            actualFitness = fitnessEvaluator.evaluar(actual, mapaAeropuertos);

            if (actualFitness < mejorFitnessGlobal) {
                mejor = actual.clonar();
                mejorFitnessGlobal = actualFitness;
                mejorasGlobales++;
            }

            listaTabu.add(mejorMovimiento.getIdMovimientoTabu());

            if (listaTabu.size() > tenure) {
                Iterator<String> it = listaTabu.iterator();
                it.next();
                it.remove();
            }
        }

        long tiempoTotal = System.currentTimeMillis() - inicio;
//        System.out.println("[METRICA TABU] enviosRecibidos=" + envios.size()
//                + " iteracionesConfiguradas=" + iteraciones
//                + " tenure=" + tenure
//                + " maxVecinos=" + maxVecinos
//                + " iteracionesEjecutadas=" + iteracionesEjecutadas
//                + " vecinosGenerados=" + vecinosGenerados
//                + " vecinosEvaluados=" + vecinosEvaluados
//                + " movimientosAceptados=" + movimientosAceptados
//                + " mejorasGlobales=" + mejorasGlobales
//                + " mejorFitnessFinal=" + mejorFitnessGlobal
//                + " tiempoTotalMs=" + tiempoTotal);
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
                            envio, itinerariosPorRuta, mapaAeropuertos)
                    .stream()
                    .filter(i -> PlanificadorUtils.itinerarioTieneCapacidad(i, envio, cargaAcumulada))
                    // Ordenar por duración ascendente = itinerario más rápido primero
                    .sorted(Comparator.comparingDouble(
                            i -> PlanificadorUtils.calcularDuracionItinerarioHoras(i)))
                    .toList();

            if (posibles.isEmpty()) {
                solucion.agregarAsignacion(envio, null);
            } else {
                // Elegir aleatoriamente entre el top 20% de mejores itinerarios
                int limite = Math.max(1, (int) Math.ceil(0.2 * posibles.size()));
                Itinerario elegido = posibles.get(random.nextInt(limite));
                PlanificadorUtils.acumularCargaItinerario(elegido, envio, cargaAcumulada);
                solucion.agregarAsignacion(envio, elegido);
            }
        }

        return solucion;
    }
}
