package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;
import pe.edu.pucp.inf.bagcontrol.planificacion.metricas.MetricasPlanificacionBloque;
import pe.edu.pucp.inf.bagcontrol.planificacion.metricas.PlanificacionInstrumentacion;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class GRASPSearch {

    private final FitnessEvaluator fitnessEvaluator;
    private final Random random = new Random();

    public SolucionRuta ejecutar(List<Envio> envios, Map<String, List<Itinerario>> itinerariosPorRuta, List<Aeropuerto> aeropuertos) {
        return ejecutarConParametros(envios, itinerariosPorRuta, aeropuertos, 30, 50, 0.4);
    }

    public SolucionRuta ejecutarConParametros(
            List<Envio> envios,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            List<Aeropuerto> aeropuertos,
            int iteraciones,
            int maxVecinos,
            double alpha
    ) {
        long inicio = System.currentTimeMillis();
        MetricasPlanificacionBloque metricasBloque = PlanificacionInstrumentacion.actualOIniciar();
        PlanificadorUtils.reiniciarMetricasRendimiento();
        SolucionRuta mejorSolucion = null;
        int iteracionesEjecutadas = 0;
        int mejorasAceptadasLocal = 0;
        int vecinosGenerados = 0;
        int vecinosEvaluados = 0;

        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

        for (int i = 0; i < iteraciones; i++) {
            iteracionesEjecutadas++;
            long inicioConstruccion = System.currentTimeMillis();
            SolucionRuta solucion = construirSolucion(envios, itinerariosPorRuta, mapaAeropuertos, alpha);
            metricasBloque.sumarConstruccionInicialMs(System.currentTimeMillis() - inicioConstruccion);
            ResultadoBusquedaLocal resultadoLocal = busquedaLocal(solucion, itinerariosPorRuta, mapaAeropuertos, maxVecinos);
            solucion = resultadoLocal.solucion();
            mejorasAceptadasLocal += resultadoLocal.mejorasAceptadas();
            vecinosGenerados += resultadoLocal.vecinosGenerados();
            vecinosEvaluados += resultadoLocal.vecinosEvaluados();

            fitnessEvaluator.evaluar(solucion, mapaAeropuertos);

            if (mejorSolucion == null || solucion.getFitness() < mejorSolucion.getFitness()) {
                mejorSolucion = solucion.clonar();
            }
        }
        metricasBloque.sumarGraspMs(System.currentTimeMillis() - inicio);
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

    private ResultadoBusquedaLocal busquedaLocal(
            SolucionRuta solucion,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos,
            int maxVecinos
    ) {
        SolucionRuta mejor = solucion.clonar();
        double mejorFitness = fitnessEvaluator.evaluar(mejor, mapaAeropuertos);
        Map<String, Integer> inventarioInicial = PlanificadorUtils.construirInventarioInicial(
                mejor.getAsignaciones().stream().map(asignacion -> asignacion.getEnvio()).toList(), Map.of());
        int mejorasAceptadas = 0;
        int vecinosGenerados = 0;
        int vecinosEvaluados = 0;

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
            vecinosGenerados += vecinos.size();

            for (var movimiento : vecinos) {
                vecinosEvaluados++;
                SolucionRuta candidato = mejor.clonar();
                boolean movimientoInvalido = candidato.aplicarMovimientoDefinitivo(
                        movimiento, mapaAeropuertos, inventarioInicial, Set.of());
                if (movimientoInvalido || !solucionValida(candidato, mapaAeropuertos)) {
                    continue;
                }
                double fitnessCandidato = fitnessEvaluator.evaluar(candidato, mapaAeropuertos);

                if (fitnessCandidato < mejorFitness) {
                    mejor = candidato;
                    mejorFitness = fitnessCandidato;
                    mejora = true;
                    mejorasAceptadas++;
                    break;
                }
            }
        }
        return new ResultadoBusquedaLocal(mejor, mejorasAceptadas, vecinosGenerados, vecinosEvaluados);
    }

    private boolean solucionValida(SolucionRuta solucion, Map<String, Aeropuerto> mapaAeropuertos) {
        if (!PlanificadorUtils.solucionRespetaCapacidadVuelos(solucion)) return false;
        for (var asignacion : solucion.getAsignaciones()) {
            Itinerario itinerario = asignacion.getItinerario();
            if (itinerario == null) continue;
            List<VueloInstanciado> vuelos = itinerario.getVuelos();
            if (vuelos == null || vuelos.isEmpty()
                    || !asignacion.getEnvio().getOrigenIata().equals(itinerario.getOrigenIata())
                    || !asignacion.getEnvio().getDestinoIata().equals(itinerario.getDestinoIata())
                    || itinerario.contieneVueloCancelado()
                    || itinerario.getFechaHoraSalidaUtc().isBefore(
                            PlanificadorUtils.obtenerFechaIngresoUtc(asignacion.getEnvio()))
                    || itinerario.getFechaHoraLlegadaUtc().isAfter(
                            PlanificadorUtils.calcularDeadlineSla(asignacion.getEnvio(), mapaAeropuertos))) {
                return false;
            }
            for (int i = 1; i < vuelos.size(); i++) {
                VueloInstanciado anterior = vuelos.get(i - 1);
                VueloInstanciado siguiente = vuelos.get(i);
                long esperaMinutos = java.time.Duration.between(
                        anterior.getFechaHoraLlegadaUtc(), siguiente.getFechaHoraSalidaUtc()).toMinutes();
                if (!anterior.getDestinoIata().equals(siguiente.getOrigenIata())
                        || esperaMinutos < 30 || esperaMinutos > 12 * 60) return false;
            }
        }
        return true;
    }

    private record ResultadoBusquedaLocal(
            SolucionRuta solucion,
            int mejorasAceptadas,
            int vecinosGenerados,
            int vecinosEvaluados
    ) {
    }
}
