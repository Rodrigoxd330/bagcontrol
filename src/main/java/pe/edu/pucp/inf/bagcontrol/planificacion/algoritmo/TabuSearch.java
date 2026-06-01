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
        Map<String, Integer> inventarioInicial = PlanificadorUtils.construirInventarioInicial(envios, Map.of());
        return ejecutarConParametros(envios, itinerariosPorRuta, aeropuertos, inventarioInicial, Set.of(), 120, 12, 50);
    }

    public SolucionRuta ejecutar(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            List<Aeropuerto> aeropuertos,
            Map<String, Integer> inventarioInicial
    ) {
        return ejecutar(envios, itinerariosPorRuta, aeropuertos, inventarioInicial, Set.of());
    }

    public SolucionRuta ejecutar(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            List<Aeropuerto> aeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos
    ) {
        return ejecutarConParametros(
                envios, itinerariosPorRuta, aeropuertos, inventarioInicial, enviosNuevos, 120, 12, 50
        );
    }

    public SolucionRuta ejecutarConParametros(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            List<Aeropuerto> aeropuertos,
            int iteraciones,
            int tenure,
            int maxVecinos
    ) {
        Map<String, Integer> inventarioInicial = PlanificadorUtils.construirInventarioInicial(envios, Map.of());
        return ejecutarConParametros(
                envios, itinerariosPorRuta, aeropuertos, inventarioInicial, Set.of(), iteraciones, tenure, maxVecinos
        );
    }

    public SolucionRuta ejecutarConParametros(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            List<Aeropuerto> aeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos,
            int iteraciones,
            int tenure,
            int maxVecinos
    ) {
        long inicio = System.currentTimeMillis();
        Set<String> listaTabu = new LinkedHashSet<>();
        int iteracionesEjecutadas = 0;
        int vecinosGenerados = 0;
        int vecinosEvaluados = 0;
        int rutasDescartadasPorCapacidadAeropuerto = 0;
        int movimientosAceptados = 0;
        int mejorasGlobales = 0;

        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

        ResultadoSolucionInicial resultadoInicial =
                generarSolucionInicial(envios, itinerariosPorRuta, mapaAeropuertos, inventarioInicial, enviosNuevos);
        SolucionRuta actual = resultadoInicial.solucion();
        rutasDescartadasPorCapacidadAeropuerto += resultadoInicial.rutasDescartadasPorCapacidadAeropuerto();
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
                if (!PlanificadorUtils.solucionRespetaCapacidadAeropuertos(
                        actual, mapaAeropuertos, inventarioInicial, enviosNuevos
                )) {
                    rutasDescartadasPorCapacidadAeropuerto++;
                    actual.deshacerMovimiento(mov);
                    actual.setFitness(actualFitness);
                    continue;
                }
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
        System.out.println("[METRICA TABU] enviosRecibidos=" + envios.size()
                + " iteracionesConfiguradas=" + iteraciones
                + " tenure=" + tenure
                + " maxVecinos=" + maxVecinos
                + " iteracionesEjecutadas=" + iteracionesEjecutadas
                + " vecinosGenerados=" + vecinosGenerados
                + " vecinosEvaluados=" + vecinosEvaluados
                + " movimientosAceptados=" + movimientosAceptados
                + " mejorasGlobales=" + mejorasGlobales
                + " rutasDescartadasPorCapacidadAeropuerto=" + rutasDescartadasPorCapacidadAeropuerto
                + " enviosPendientesPorCapacidad=" + resultadoInicial.enviosPendientesPorCapacidad()
                + " enviosReplanificadosPorCapacidad=" + movimientosAceptados
                + " mejorFitnessFinal=" + mejorFitnessGlobal
                + " tiempoTotalMs=" + tiempoTotal);
        return mejor;
    }

    private ResultadoSolucionInicial generarSolucionInicial(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos
    ) {
        SolucionRuta solucion = new SolucionRuta();
        Map<VueloInstanciado, Integer> cargaAcumulada = new HashMap<>();
        int rutasDescartadasPorCapacidadAeropuerto = 0;
        int enviosPendientesPorCapacidad = 0;

        List<Envio> enviosPriorizados = envios.stream()
                .sorted(Comparator
                        .comparing((Envio envio) -> PlanificadorUtils.calcularDeadlineSla(envio, mapaAeropuertos))
                        .thenComparing(Comparator.comparingInt(Envio::getCantidadMaletas).reversed()))
                .toList();

        for (Envio envio : enviosPriorizados) {
            List<Itinerario> posibles = PlanificadorUtils.buscarItinerariosViablesParaEnvio(
                            envio, itinerariosPorRuta, mapaAeropuertos)
                    .stream()
                    .filter(i -> PlanificadorUtils.itinerarioTieneCapacidad(i, envio, cargaAcumulada))
                    // Ordenar por duración ascendente = itinerario más rápido primero
                    .sorted(Comparator
                            .comparing(Itinerario::getFechaHoraSalidaUtc)
                            .thenComparing(Itinerario::getFechaHoraLlegadaUtc)
                            .thenComparingInt(Itinerario::getCantidadVuelos))
                    .toList();

            List<Itinerario> viablesPorAeropuerto = new ArrayList<>();
            for (Itinerario posible : posibles) {
                solucion.agregarAsignacion(envio, posible);
                boolean capacidadDisponible = PlanificadorUtils.solucionRespetaCapacidadAeropuertos(
                        solucion, mapaAeropuertos, inventarioInicial, enviosNuevos
                );
                solucion.getAsignaciones().remove(solucion.getAsignaciones().size() - 1);
                if (capacidadDisponible) {
                    viablesPorAeropuerto.add(posible);
                } else {
                    rutasDescartadasPorCapacidadAeropuerto++;
                }
            }

            if (viablesPorAeropuerto.isEmpty()) {
                solucion.agregarAsignacion(envio, null);
                if (!posibles.isEmpty()) {
                    enviosPendientesPorCapacidad++;
                }
            } else {
                // Elegir aleatoriamente entre el top 20% de mejores itinerarios
                Itinerario elegido = viablesPorAeropuerto.get(0);
                PlanificadorUtils.acumularCargaItinerario(elegido, envio, cargaAcumulada);
                solucion.agregarAsignacion(envio, elegido);
            }
        }

        return new ResultadoSolucionInicial(
                solucion, rutasDescartadasPorCapacidadAeropuerto, enviosPendientesPorCapacidad
        );
    }

    private record ResultadoSolucionInicial(
            SolucionRuta solucion,
            int rutasDescartadasPorCapacidadAeropuerto,
            int enviosPendientesPorCapacidad
    ) {
    }
}
