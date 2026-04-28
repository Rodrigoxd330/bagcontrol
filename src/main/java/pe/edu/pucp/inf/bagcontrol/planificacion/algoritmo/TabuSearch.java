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
import java.util.HashMap;
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
        // FIX Bug B: LinkedHashSet mantiene orden de inserción → evicción FIFO correcta del tenure
        Set<String> listaTabu = new LinkedHashSet<>();

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

            // FIX Bug B: con LinkedHashSet, iterator().next() devuelve el más antiguo → FIFO correcto
            if (listaTabu.size() > tenure) {
                Iterator<String> it = listaTabu.iterator();
                it.next();
                it.remove();
            }
        }

        imprimirDiagnosticoTabu(mejor, envios, mejorFitnessGlobal, mapaAeropuertos);

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

    /**
     * Imprime un desglose detallado de cuánto aporta cada componente al fitness total.
     *
     * Estrategia opción 1: lee sinItinerarioCount y excedeSlaCount directamente de
     * SolucionRuta (escritos por FitnessEvaluator.evaluar). Los contadores de escala
     * y sobrecarga se recalculan aquí en O(n) ya que FitnessEvaluator no los persiste.
     */
    private void imprimirDiagnosticoTabu(
            SolucionRuta solucion,
            List<Envio> envios,
            double fitnessGlobal,
            Map<String, Aeropuerto> mapaAeropuertos
    ) {
        int total = envios.size();

        // --- Opción 1: contadores ya escritos por FitnessEvaluator en SolucionRuta ---
        int sinItinerario = solucion.getSinItinerarioCount();
        int excedeSla     = solucion.getExcedeSlaCount();

        // --- Contadores que FitnessEvaluator no persiste: se recalculan una sola vez ---
        long directos = solucion.getAsignaciones().stream()
                .filter(a -> a.getItinerario() != null)
                .filter(a -> a.getItinerario().getCantidadVuelos() == 1)
                .count();

        long conEscala = solucion.getAsignaciones().stream()
                .filter(a -> a.getItinerario() != null)
                .filter(a -> a.getItinerario().getCantidadVuelos() > 1)
                .count();

        long totalEscalas = solucion.getAsignaciones().stream()
                .filter(a -> a.getItinerario() != null)
                .mapToLong(a -> Math.max(0, a.getItinerario().getCantidadVuelos() - 1))
                .sum();

        // Carga por vuelo y aeropuerto para calcular penalizaciones de sobrecarga
        Map<pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado, Integer> cargaPorVuelo = new HashMap<>();
        Map<String, Integer> cargaPorAeropuerto = new HashMap<>();
        solucion.getAsignaciones().stream()
                .filter(a -> a.getItinerario() != null)
                .forEach(a -> {
                    a.getItinerario().getVuelos().forEach(v ->
                            cargaPorVuelo.merge(v, a.getEnvio().getCantidadMaletas(), Integer::sum));
                    cargaPorAeropuerto.merge(
                            a.getItinerario().getDestinoIata(),
                            a.getEnvio().getCantidadMaletas(),
                            Integer::sum);
                });

        long vuelosSobrecargados = cargaPorVuelo.entrySet().stream()
                .filter(e -> e.getValue() > e.getKey().getCapacidadMax())
                .count();

        double penSobrecargaVuelo = cargaPorVuelo.entrySet().stream()
                .filter(e -> e.getValue() > e.getKey().getCapacidadMax())
                .mapToDouble(e -> Math.pow(e.getValue() - e.getKey().getCapacidadMax(), 2) * 50.0)
                .sum();

        long aeropuertosSobrecargados = cargaPorAeropuerto.entrySet().stream()
                .filter(e -> {
                    var ap = mapaAeropuertos.get(e.getKey());
                    return ap != null && e.getValue() > ap.getCapacidadAlmacen();
                })
                .count();

        double penSobrecargaAeropuerto = cargaPorAeropuerto.entrySet().stream()
                .filter(e -> {
                    var ap = mapaAeropuertos.get(e.getKey());
                    return ap != null && e.getValue() > ap.getCapacidadAlmacen();
                })
                .mapToDouble(e -> (e.getValue() - mapaAeropuertos.get(e.getKey()).getCapacidadAlmacen()) * 10.0)
                .sum();

        // --- Desglose de penalizaciones (mismas constantes que FitnessEvaluator) ---
        double penTotalSinItinerario = sinItinerario * 10000.0;
        double penTotalSla           = excedeSla     *  5000.0;
        double penTotalEscalas       = totalEscalas  *    10.0;
        double fitnessDuracionBase   = fitnessGlobal
                - penTotalSinItinerario
                - penTotalSla
                - penTotalEscalas
                - penSobrecargaVuelo
                - penSobrecargaAeropuerto;

        System.out.println("========== [TABU DIAGNÓSTICO DETALLADO] ==========");
        System.out.printf("Total envíos              : %d%n", total);
        System.out.println("--------------------------------------------------");
        System.out.printf("Sin itinerario            : %d (%.1f%%)  →  pen %.0f%n",
                sinItinerario,
                total > 0 ? 100.0 * sinItinerario / total : 0.0,
                penTotalSinItinerario);
        System.out.printf("SLA excedidos             : %d (%.1f%%)  →  pen %.0f%n",
                excedeSla,
                total > 0 ? 100.0 * excedeSla / total : 0.0,
                penTotalSla);
        System.out.printf("Escalas extra (tramos)    : %d           →  pen %.0f%n",
                totalEscalas, penTotalEscalas);
        System.out.printf("Vuelos sobrecargados      : %d           →  pen %.0f%n",
                vuelosSobrecargados, penSobrecargaVuelo);
        System.out.printf("Aeropuertos sobrecargados : %d           →  pen %.0f%n",
                aeropuertosSobrecargados, penSobrecargaAeropuerto);
        System.out.printf("Duración base (suma hrs)  : %.2f%n", fitnessDuracionBase);
        System.out.println("--------------------------------------------------");
        System.out.printf("Vuelos directos           : %d%n", directos);
        System.out.printf("Vuelos con escala         : %d%n", conEscala);
        System.out.println("--------------------------------------------------");
        System.out.printf("FITNESS TOTAL             : %.2f%n", fitnessGlobal);
        System.out.println("==================================================");
    }
}
