package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloFactory;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo.GRASPSearch;
import pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo.TabuSearch;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.AsignacionPlanDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.PlanResultadoDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.ResultadoSimulacionDTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlanificadorService {

    private final EnvioDataStore envioDataStore;
    private final VueloRepository vueloRepository;
    private final AeropuertoRepository aeropuertoRepository;
    private final VueloFactory vueloFactory;
    private final GRASPSearch graspSearch;
    private final TabuSearch tabuSearch;

    public ResultadoSimulacionDTO ejecutarSimulacion(LocalDate fechaInicio, int cantidadDias) {

        if (cantidadDias <= 0) {
            throw new IllegalArgumentException("La cantidad de días debe ser mayor que 0.");
        }

        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(cantidadDias).atStartOfDay();

        var envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        var vuelosBase = vueloRepository.findAll();
        var aeropuertos = aeropuertoRepository.findAll();

        List<VueloInstanciado> vuelosInstanciados = generarVuelosInstanciados(vuelosBase, fechaInicio, cantidadDias);

        System.out.println("========================================");
        System.out.println("VENTANA DE SIMULACIÓN");
        System.out.println("Inicio: " + inicio);
        System.out.println("Fin   : " + fin);
        System.out.println("Días simulados: " + cantidadDias);
        System.out.println("Envios usados: " + envios.size());
        System.out.println("Vuelos base disponibles: " + vuelosBase.size());
        System.out.println("Vuelos instanciados: " + vuelosInstanciados.size());
        System.out.println("Aeropuertos cargados: " + aeropuertos.size());
        System.out.println("========================================");

        System.out.println("\n=== EJECUTANDO GRASP ===");
        var solucionGrasp = graspSearch.ejecutar(envios, vuelosInstanciados, aeropuertos);
        System.out.println("Fitness GRASP: " + solucionGrasp.getFitness());
        System.out.println("Asignaciones GRASP: " + solucionGrasp.getAsignaciones().size());

        System.out.println("\n=== EJECUTANDO TABU ===");
        var solucionTabu = tabuSearch.ejecutar(envios, vuelosInstanciados, aeropuertos);
        System.out.println("Fitness TABU: " + solucionTabu.getFitness());
        System.out.println("Asignaciones TABU: " + solucionTabu.getAsignaciones().size());

        String ganador;
        if (solucionGrasp.getFitness() < solucionTabu.getFitness()) {
            ganador = "GRASP";
        } else if (solucionTabu.getFitness() < solucionGrasp.getFitness()) {
            ganador = "TABU";
        } else {
            ganador = "EMPATE";
        }

        return new ResultadoSimulacionDTO(
                inicio.toString(),
                fin.toString(),
                envios.size(),
                vuelosBase.size(),
                vuelosInstanciados.size(),
                aeropuertos.size(),
                solucionGrasp.getFitness(),
                solucionGrasp.getAsignaciones().size(),
                solucionTabu.getFitness(),
                solucionTabu.getAsignaciones().size(),
                ganador
        );
    }

    public PlanResultadoDTO obtenerPlan(String algoritmo, LocalDate fechaInicio, int dias) {

        if (dias <= 0) {
            throw new IllegalArgumentException("La cantidad de días debe ser mayor que 0.");
        }

        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(dias).atStartOfDay();

        var envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        var vuelosBase = vueloRepository.findAll();
        var aeropuertos = aeropuertoRepository.findAll();

        List<VueloInstanciado> vuelosInstanciados = generarVuelosInstanciados(vuelosBase, fechaInicio, dias);

        var solucion = algoritmo.equalsIgnoreCase("TABU")
                ? tabuSearch.ejecutar(envios, vuelosInstanciados, aeropuertos)
                : graspSearch.ejecutar(envios, vuelosInstanciados, aeropuertos);

        var plan = solucion.getAsignaciones().stream()
                .map(asignacion -> {
                    var envio = asignacion.getEnvio();
                    var vuelo = asignacion.getVuelo();

                    if (vuelo == null) {
                        return new AsignacionPlanDTO(
                                envio.getIdPedido(),
                                envio.getOrigenIata(),
                                envio.getDestinoIata(),
                                envio.getCantidadMaletas(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                "SIN_VUELO_ASIGNADO"
                        );
                    }

                    return new AsignacionPlanDTO(
                            envio.getIdPedido(),
                            envio.getOrigenIata(),
                            envio.getDestinoIata(),
                            envio.getCantidadMaletas(),
                            vuelo.getCodigoBase(),
                            vuelo.getOrigenIata(),
                            vuelo.getDestinoIata(),
                            vuelo.getFechaHoraSalida().toString(),
                            vuelo.getFechaHoraLlegada().toString(),
                            "ASIGNADO"
                    );
                })
                .toList();

        return new PlanResultadoDTO(
                algoritmo.toUpperCase(),
                solucion.getFitness(),
                solucion.getAsignaciones().size(),
                plan
        );
    }

    public void ejecutarPrueba() {
        LocalDate fechaInicio = LocalDate.of(2026, 2, 2);
        int cantidadDias = 1;

        System.out.println("=== INICIANDO PRUEBA ===");
        ResultadoSimulacionDTO resultado = ejecutarSimulacion(fechaInicio, cantidadDias);

        System.out.println("=== RESULTADO DE PRUEBA ===");
        System.out.println("Ganador: " + resultado.getGanador());
        System.out.println("Fitness GRASP: " + resultado.getFitnessGrasp());
        System.out.println("Fitness TABU: " + resultado.getFitnessTabu());
        System.out.println("=== FIN DE PRUEBA ===");
    }

    private List<VueloInstanciado> generarVuelosInstanciados(List<?> vuelosBase, LocalDate fechaInicio, int cantidadDias) {
        List<VueloInstanciado> vuelosInstanciados = new ArrayList<>();

        for (int i = 0; i < cantidadDias; i++) {
            LocalDate fechaActual = fechaInicio.plusDays(i);
            vuelosInstanciados.addAll(vueloFactory.crearInstanciasDelDia((List) vuelosBase, fechaActual));
        }

        return vuelosInstanciados;
    }
}