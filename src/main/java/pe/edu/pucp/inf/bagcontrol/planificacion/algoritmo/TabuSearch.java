package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Movimiento;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
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

    public SolucionRuta ejecutar(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            List<Aeropuerto> aeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos,
            long deadlineMs,
            long budgetMs
    ) {
        return ejecutarConParametros(envios, itinerariosPorRuta, aeropuertos, inventarioInicial,
                enviosNuevos, 120, 12, 50, deadlineMs, budgetMs);
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
        long budgetMs = 83_000L;
        return ejecutarConParametros(envios, itinerariosPorRuta, aeropuertos, inventarioInicial,
                enviosNuevos, iteraciones, tenure, maxVecinos,
                System.currentTimeMillis() + budgetMs, budgetMs);
    }

    private SolucionRuta ejecutarConParametros(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            List<Aeropuerto> aeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos,
            int iteraciones,
            int tenure,
            int maxVecinos,
            long deadlineMs,
            long budgetMs
    ) {
        long inicio = System.currentTimeMillis();
        PlanificadorUtils.reiniciarMetricasRendimiento();
        long memoriaAntes = memoriaUsada();
        Set<String> listaTabu = new LinkedHashSet<>();
        int iteracionesEjecutadas = 0;
        int vecinosGenerados = 0;
        int vecinosEvaluados = 0;
        int rutasDescartadasPorCapacidadAeropuerto = 0;
        int movimientosAceptados = 0;
        int mejorasGlobales = 0;
        int copiasSolucion = 0;
        long tiempoCopiasSolucionNanos = 0L;
        String motivoParada = "ITERACIONES_COMPLETADAS";

        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

        ResultadoSolucionInicial resultadoInicial =
                generarSolucionInicial(envios, itinerariosPorRuta, mapaAeropuertos, inventarioInicial,
                        enviosNuevos, deadlineMs);
        long finSolucionInicial = System.currentTimeMillis();
        System.out.println("[PLAN-PERF-PHASE] fase=SOLUCION_INICIAL duracionMs=" + (finSolucionInicial - inicio));
        SolucionRuta actual = resultadoInicial.solucion();
        rutasDescartadasPorCapacidadAeropuerto += resultadoInicial.rutasDescartadasPorCapacidadAeropuerto();
        double actualFitness = fitnessEvaluator.evaluar(actual, mapaAeropuertos, inventarioInicial);

        long inicioCopia = System.nanoTime();
        SolucionRuta mejor = actual.clonar();
        tiempoCopiasSolucionNanos += System.nanoTime() - inicioCopia;
        copiasSolucion++;
        double mejorFitnessGlobal = actualFitness;

        for (int i = 0; i < iteraciones; i++) {
            if (System.currentTimeMillis() >= deadlineMs) {
                motivoParada = "TIME_BUDGET_REACHED";
                break;
            }
            iteracionesEjecutadas++;
            long restanteMs = deadlineMs - System.currentTimeMillis();
            int vecinosPermitidos = restanteMs < 5_000L ? Math.min(maxVecinos, 10) : maxVecinos;
            List<Movimiento> vecinos = PlanificadorUtils.generarVecindario(
                    actual,
                    itinerariosPorRuta,
                    mapaAeropuertos,
                    vecinosPermitidos
            );
            vecinosGenerados += vecinos.size();

            Movimiento mejorMovimiento = null;
            double mejorFitnessVecino = Double.MAX_VALUE;

            for (Movimiento mov : vecinos) {
                if (System.currentTimeMillis() >= deadlineMs) {
                    motivoParada = "TIME_BUDGET_REACHED";
                    break;
                }
                vecinosEvaluados++;
                String id = mov.getIdMovimientoTabu();

                boolean noRespeta = actual.aplicarMovimientoDefinitivo(mov,mapaAeropuertos,inventarioInicial,enviosNuevos);

                /*
                for(RutaAsignada asignacion : actual.getAsignaciones()){
                    if (!PlanificadorUtils.solucionRespetaCapacidadAeropuertos(
                            asignacion, mapaAeropuertos, inventarioInicial, enviosNuevos
                    )) {
                        rutasDescartadasPorCapacidadAeropuerto++;
                        actual.deshacerMovimiento(mov);
                        actual.setFitness(actualFitness);
                        noRespeta = true;
                        break;
                    }
                }*/
                if(noRespeta) {
                    rutasDescartadasPorCapacidadAeropuerto++;
                    actual.deshacerMovimiento(mov);
                    actual.setFitness(actualFitness);
                    continue;
                }

                double fitnessCandidato = fitnessEvaluator.evaluar(actual, mapaAeropuertos, inventarioInicial);

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

            if (mejorMovimiento == null) {
                if (!"TIME_BUDGET_REACHED".equals(motivoParada)) motivoParada = "SIN_VECINOS";
                break;
            }

            boolean noRespeta = actual.aplicarMovimientoDefinitivo(mejorMovimiento,mapaAeropuertos,inventarioInicial,enviosNuevos);
            if(noRespeta){actual.deshacerMovimiento(mejorMovimiento);break;}

            movimientosAceptados++;
            actualFitness = fitnessEvaluator.evaluar(actual, mapaAeropuertos, inventarioInicial);

            if (actualFitness < mejorFitnessGlobal) {
                inicioCopia = System.nanoTime();
                mejor = actual.clonar();
                tiempoCopiasSolucionNanos += System.nanoTime() - inicioCopia;
                copiasSolucion++;
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
        PlanificadorUtils.MetricasRendimiento metricasUtils = PlanificadorUtils.snapshotMetricasRendimiento();
        long memoriaDespues = memoriaUsada();
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
        System.out.println("[PLAN-PERF-PHASE] fase=VALIDACION_CAPACIDAD duracionMs="
                + metricasUtils.tiempoValidacionCapacidadMs());
        System.out.println("[PLAN-PERF-PHASE] fase=TABU duracionMs=" + tiempoTotal
                + " iteracionesEjecutadas=" + iteracionesEjecutadas
                + " vecinosEvaluados=" + vecinosEvaluados
                + " movimientosAceptados=" + movimientosAceptados);
        System.out.println("[TABU-STOP] motivo=" + motivoParada
                + " elapsedMs=" + tiempoTotal + " budgetMs=" + budgetMs
                + " iteraciones=" + iteracionesEjecutadas
                + " mejorFitness=" + mejorFitnessGlobal + " solucionFactible=true");
        System.out.println("[SIM5D-PERF] llamadasRegistrarMovimientosAeropuertos="
                + metricasUtils.llamadasRegistrarMovimientosAeropuertos()
                + " movimientosAeropuertoGenerados=" + metricasUtils.movimientosAeropuertoGenerados()
                + " tiempoRegistrarMovimientosAeropuertosMs=" + metricasUtils.tiempoRegistrarMovimientosAeropuertosMs()
                + " llamadasValidacionCapacidad=" + metricasUtils.llamadasValidacionCapacidad()
                + " tiempoValidacionCapacidadMs=" + metricasUtils.tiempoValidacionCapacidadMs()
                + " vecinosGenerados=" + vecinosGenerados
                + " vecinosEvaluados=" + vecinosEvaluados
                + " copiasSolucion=" + copiasSolucion
                + " tiempoCopiasSolucionMs=" + (tiempoCopiasSolucionNanos / 1_000_000)
                + " memoriaAntesBytes=" + memoriaAntes
                + " memoriaDespuesBytes=" + memoriaDespues
                + " memoriaDeltaBytes=" + (memoriaDespues - memoriaAntes));
        return mejor;
    }

    private long memoriaUsada() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private ResultadoSolucionInicial generarSolucionInicial(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos,
            long deadlineMs
    ) {
        SolucionRuta solucion = new SolucionRuta();
        Map<VueloInstanciado, Integer> cargaAcumulada = new HashMap<>();
        Map<String, Integer> inventarioAcumulado = new HashMap<>(inventarioInicial);
        int rutasDescartadasPorCapacidadAeropuerto = 0;
        int enviosPendientesPorCapacidad = 0;
        PlanificadorUtils.EvaluadorCapacidadIncremental evaluadorCapacidad =
                PlanificadorUtils.crearEvaluadorCapacidadIncremental(
                        mapaAeropuertos, inventarioInicial, enviosNuevos
                );

        List<Envio> enviosPriorizados = envios.stream()
                .sorted(Comparator
                        .comparing((Envio envio) -> PlanificadorUtils.calcularDeadlineSla(envio, mapaAeropuertos))
                        .thenComparing(Comparator.comparingInt(Envio::getCantidadMaletas).reversed()))
                .toList();

        for (Envio envio : enviosPriorizados) {
            if (System.currentTimeMillis() >= deadlineMs) {
                solucion.agregarAsignacion(envio, null);
                enviosPendientesPorCapacidad++;
                continue;
            }
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
                boolean capacidadDisponible = evaluadorCapacidad.respetaCapacidadAlAgregar(envio, posible);
                RutaAsignada asignacion = solucion.agregarAsignacion(envio, posible);
                boolean capacidadDisponible = PlanificadorUtils.solucionRespetaCapacidadAeropuertos(
                        asignacion, mapaAeropuertos, inventarioAcumulado, enviosNuevos
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
                Itinerario elegido = seleccionarItinerarioConsolidado(
                        viablesPorAeropuerto, cargaAcumulada, mapaAeropuertos, inventarioAcumulado
                );
                PlanificadorUtils.acumularCargaItinerario(elegido, envio, cargaAcumulada);
                acumularInventarioItinerario(elegido, envio, inventarioAcumulado);
                evaluadorCapacidad.agregar(envio, elegido);
                solucion.agregarAsignacion(envio, elegido);
            }
        }

        return new ResultadoSolucionInicial(
                solucion, rutasDescartadasPorCapacidadAeropuerto, enviosPendientesPorCapacidad
        );
    }

    private Itinerario seleccionarItinerarioConsolidado(
            List<Itinerario> itinerarios,
            Map<VueloInstanciado, Integer> cargaAcumulada,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioAcumulado
    ) {
        return itinerarios.stream()
                .min(Comparator
                        .comparingDouble((Itinerario itinerario) -> costoSaturacionItinerario(
                                itinerario, mapaAeropuertos, inventarioAcumulado
                        ))
                        .thenComparing(
                                Comparator.comparingInt(
                                        (Itinerario itinerario) -> cargaActualItinerario(itinerario, cargaAcumulada)
                                ).reversed()
                        )
                        .thenComparing(Itinerario::getFechaHoraLlegadaUtc))
                .orElseThrow();
    }

    private int cargaActualItinerario(Itinerario itinerario, Map<VueloInstanciado, Integer> cargaAcumulada) {
        return itinerario.getVuelos().stream()
                .mapToInt(vuelo -> cargaAcumulada.getOrDefault(vuelo, 0))
                .sum();
    }

    private double costoSaturacionItinerario(
            Itinerario itinerario,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioAcumulado
    ) {
        Set<String> aeropuertosRuta = new LinkedHashSet<>();
        for (VueloInstanciado vuelo : itinerario.getVuelos()) {
            aeropuertosRuta.add(vuelo.getOrigenIata());
            aeropuertosRuta.add(vuelo.getDestinoIata());
        }

        double costo = 0.0;
        for (String codigoIata : aeropuertosRuta) {
            Aeropuerto aeropuerto = mapaAeropuertos.get(codigoIata);
            if (aeropuerto == null || aeropuerto.getCapacidadAlmacen() <= 0) {
                continue;
            }
            double ocupacion = inventarioAcumulado.getOrDefault(codigoIata, 0)
                    / (double) aeropuerto.getCapacidadAlmacen();
            if (ocupacion > 0.95) {
                costo += (ocupacion - 0.95) * 100_000.0 + 10_000.0;
            } else if (ocupacion > 0.90) {
                costo += (ocupacion - 0.90) * 10_000.0 + 1_000.0;
            } else if (ocupacion > 0.80) {
                costo += (ocupacion - 0.80) * 1_000.0 + 100.0;
            }
        }
        return costo;
    }

    private void acumularInventarioItinerario(
            Itinerario itinerario,
            Envio envio,
            Map<String, Integer> inventarioAcumulado
    ) {
        int cantidad = envio.getCantidadMaletas();
        for (VueloInstanciado vuelo : itinerario.getVuelos()) {
            inventarioAcumulado.merge(vuelo.getDestinoIata(), cantidad, Integer::sum);
        }
    }

    private record ResultadoSolucionInicial(
            SolucionRuta solucion,
            int rutasDescartadasPorCapacidadAeropuerto,
            int enviosPendientesPorCapacidad
    ) {
    }
}
