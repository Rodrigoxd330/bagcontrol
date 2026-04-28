package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloFactory;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo.GRASPSearch;
import pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo.TabuSearch;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.AsignacionPlanDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.PlanResultadoDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.ResultadoSimulacionDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PlanificadorService {

    private final EnvioDataStore envioDataStore;
    private final VueloRepository vueloRepository;
    private final AeropuertoRepository aeropuertoRepository;
    private final VueloFactory vueloFactory;
    private final GRASPSearch graspSearch;
    private final TabuSearch tabuSearch;
    private final ItinerarioService itinerarioService;

    public PlanResultadoDTO obtenerPlan(String algoritmo, LocalDate fechaInicio, int dias) {

        if (dias <= 0) {
            throw new IllegalArgumentException("La cantidad de días debe ser mayor que 0.");
        }

        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(dias).atStartOfDay();

        List<Envio> envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        List<Vuelo> vuelosBase = vueloRepository.findAll();
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();

        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, fechaInicio, dias);

        Map<String, List<Itinerario>> itinerariosPorRuta =
                itinerarioService.generarItinerariosPorRuta(vuelosInstanciados);

        SolucionRuta solucion = algoritmo.equalsIgnoreCase("TABU")
                ? tabuSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos)
                : graspSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);

        List<AsignacionPlanDTO> plan = solucion.getAsignaciones().stream()
                .map(asignacion -> {
                    Envio envio = asignacion.getEnvio();
                    Itinerario itinerario = asignacion.getItinerario();

                    if (itinerario == null || itinerario.getVuelos() == null || itinerario.getVuelos().isEmpty()) {
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
                                "SIN_ITINERARIO_ASIGNADO"
                        );
                    }

                    VueloInstanciado primerVuelo = itinerario.getVuelos().get(0);
                    VueloInstanciado ultimoVuelo = itinerario.getVuelos().get(itinerario.getVuelos().size() - 1);

                    String estado = itinerario.getCantidadVuelos() == 1
                            ? "ASIGNADO_DIRECTO"
                            : "ASIGNADO_CON_ESCALA_" + itinerario.getCantidadVuelos() + "_VUELOS";

                    return new AsignacionPlanDTO(
                            envio.getIdPedido(),
                            envio.getOrigenIata(),
                            envio.getDestinoIata(),
                            envio.getCantidadMaletas(),
                            primerVuelo.getCodigoBase(),
                            primerVuelo.getOrigenIata(),
                            ultimoVuelo.getDestinoIata(),
                            primerVuelo.getFechaHoraSalida().toString(),
                            ultimoVuelo.getFechaHoraLlegada().toString(),
                            estado
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

    public ResultadoSimulacionDTO ejecutarSimulacion(LocalDate fechaInicio, int cantidadDias) {

        if (cantidadDias <= 0) {
            throw new IllegalArgumentException("La cantidad de días debe ser mayor que 0.");
        }

        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(cantidadDias).atStartOfDay();

        List<Envio> envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        List<Vuelo> vuelosBase = vueloRepository.findAll();
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();

        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, fechaInicio, cantidadDias);

        Map<String, List<Itinerario>> itinerariosPorRuta =
                itinerarioService.generarItinerariosPorRuta(vuelosInstanciados);

        System.out.println("========================================");
        System.out.println("VENTANA DE SIMULACIÓN");
        System.out.println("Inicio: " + inicio);
        System.out.println("Fin   : " + fin);
        System.out.println("Días simulados: " + cantidadDias);
        System.out.println("Envios usados: " + envios.size());
        System.out.println("Vuelos base disponibles: " + vuelosBase.size());
        System.out.println("Vuelos instanciados: " + vuelosInstanciados.size());
        System.out.println("Rutas con itinerarios: " + itinerariosPorRuta.size());
        System.out.println("Aeropuertos cargados: " + aeropuertos.size());
        System.out.println("========================================");

        System.out.println("\n=== EJECUTANDO GRASP ===");

        long inicioGrasp = System.currentTimeMillis();
        SolucionRuta solucionGrasp =
                graspSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        long finGrasp = System.currentTimeMillis();

        long tiempoGrasp = finGrasp - inicioGrasp;
        int asignacionesGrasp = solucionGrasp.getAsignaciones().size();
        double vuelosPromedioGrasp = calcularVuelosPromedio(solucionGrasp, envios.size());

        System.out.println("Fitness GRASP: " + solucionGrasp.getFitness());
        System.out.println("Asignaciones GRASP: " + asignacionesGrasp);
        System.out.println("Tiempo GRASP (ms): " + tiempoGrasp);
        System.out.println("Vuelos promedio GRASP: " + vuelosPromedioGrasp);

        System.out.println("\n=== EJECUTANDO TABU ===");

        long inicioTabu = System.currentTimeMillis();
        SolucionRuta solucionTabu =
                tabuSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        long finTabu = System.currentTimeMillis();

        long tiempoTabu = finTabu - inicioTabu;
        int asignacionesTabu = solucionTabu.getAsignaciones().size();
        double vuelosPromedioTabu = calcularVuelosPromedio(solucionTabu, envios.size());

        System.out.println("Fitness TABU: " + solucionTabu.getFitness());
        System.out.println("Asignaciones TABU: " + asignacionesTabu);
        System.out.println("Tiempo TABU (ms): " + tiempoTabu);
        System.out.println("Vuelos promedio TABU: " + vuelosPromedioTabu);

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
                asignacionesGrasp,
                tiempoGrasp,
                vuelosPromedioGrasp,

                solucionTabu.getFitness(),
                asignacionesTabu,
                tiempoTabu,
                vuelosPromedioTabu,

                ganador
        );
    }

    private List<VueloInstanciado> generarVuelosInstanciados(
            List<Vuelo> vuelosBase,
            LocalDate fechaInicio,
            int cantidadDias
    ) {
        List<VueloInstanciado> vuelosInstanciados = new ArrayList<>();

        for (int i = 0; i < cantidadDias; i++) {
            LocalDate fechaActual = fechaInicio.plusDays(i);
            vuelosInstanciados.addAll(
                    vueloFactory.crearInstanciasDelDia(vuelosBase, fechaActual)
            );
        }

        return vuelosInstanciados;
    }

    private double calcularVuelosPromedio(SolucionRuta solucion, int totalEnvios) {
        if (totalEnvios == 0) {
            return 0.0;
        }

        int totalVuelosUsados = solucion.getAsignaciones().stream()
                .filter(a -> a.getItinerario() != null)
                .mapToInt(a -> a.getItinerario().getCantidadVuelos())
                .sum();

        return (double) totalVuelosUsados / totalEnvios;
    }

    public void ejecutarPrueba() {
        LocalDate fechaInicio = LocalDate.of(2026, 2, 2);
        int cantidadDias = 1;
        int iteraciones = 5;

        System.out.println("========================================");
        System.out.println("INICIANDO EXPERIMENTACIÓN (" + iteraciones + " iteraciones)");
        System.out.println("Fecha inicio: " + fechaInicio);
        System.out.println("Días: " + cantidadDias);
        System.out.println("========================================");

        int winsGrasp = 0;
        int winsTabu = 0;
        int empates = 0;

        double sumaFitnessGrasp = 0;
        double sumaFitnessTabu = 0;

        long sumaTiempoGrasp = 0;
        long sumaTiempoTabu = 0;

        for (int i = 1; i <= iteraciones; i++) {
            System.out.println("\n----------------------------------------");
            System.out.println("ITERACIÓN " + i);
            System.out.println("----------------------------------------");

            ResultadoSimulacionDTO r = ejecutarSimulacion(fechaInicio, cantidadDias);

            System.out.println("RESULTADO ITERACIÓN " + i);
            System.out.println("Ganador: " + r.getGanador());

            System.out.println("GRASP -> Fitness: " + r.getFitnessGrasp() +
                    " | Tiempo(ms): " + r.getTiempoGrasp() +
                    " | VuelosProm: " + r.getVuelosPromedioGrasp());

            System.out.println("TABU  -> Fitness: " + r.getFitnessTabu() +
                    " | Tiempo(ms): " + r.getTiempoTabu() +
                    " | VuelosProm: " + r.getVuelosPromedioTabu());

            sumaFitnessGrasp += r.getFitnessGrasp();
            sumaFitnessTabu += r.getFitnessTabu();

            sumaTiempoGrasp += r.getTiempoGrasp();
            sumaTiempoTabu += r.getTiempoTabu();

            switch (r.getGanador()) {
                case "GRASP" -> winsGrasp++;
                case "TABU" -> winsTabu++;
                default -> empates++;
            }
        }

        System.out.println("\n========================================");
        System.out.println("RESUMEN FINAL");
        System.out.println("========================================");

        System.out.println("GRASP wins: " + winsGrasp);
        System.out.println("TABU wins : " + winsTabu);
        System.out.println("Empates   : " + empates);

        System.out.println("\nPROMEDIOS:");
        System.out.println("GRASP -> Fitness Prom: " + (sumaFitnessGrasp / iteraciones) +
                " | Tiempo Prom(ms): " + (sumaTiempoGrasp / iteraciones));

        System.out.println("TABU  -> Fitness Prom: " + (sumaFitnessTabu / iteraciones) +
                " | Tiempo Prom(ms): " + (sumaTiempoTabu / iteraciones));

        System.out.println("========================================");
        System.out.println("FIN DE EXPERIMENTACIÓN");
    }
}