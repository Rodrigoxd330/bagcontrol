package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.model.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.repo.AeropuertoRepository;
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
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

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
        if (dias <= 0) throw new IllegalArgumentException("La cantidad de días debe ser mayor que 0.");

        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(dias).atStartOfDay();

        List<Envio> envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        List<Vuelo> vuelosBase = vueloRepository.findAll();
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();

        // Se mantiene el margen de 2 días para cubrir el SLA máximo de 48h [cite: 12]
        long inicioGeneracionVuelos = System.currentTimeMillis();
        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, fechaInicio, dias + 2, aeropuertos);
        long tiempoGeneracionVuelos = System.currentTimeMillis() - inicioGeneracionVuelos;

        long inicioGeneracionItinerarios = System.currentTimeMillis();
        Map<String, List<Itinerario>> itinerariosPorRuta =
                itinerarioService.generarItinerariosPorRuta(vuelosInstanciados);
        long tiempoGeneracionItinerarios = System.currentTimeMillis() - inicioGeneracionItinerarios;

        long inicioAlgoritmo = System.currentTimeMillis();
        SolucionRuta solucion = algoritmo.equalsIgnoreCase("TABU")
                ? tabuSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos)
                : graspSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        long tiempoAlgoritmo = System.currentTimeMillis() - inicioAlgoritmo;

        imprimirMetricasPlanificacion(algoritmo, fechaInicio, dias, envios, vuelosBase, vuelosInstanciados,
                aeropuertos, itinerariosPorRuta, tiempoGeneracionVuelos, tiempoGeneracionItinerarios,
                tiempoAlgoritmo, solucion);

        List<AsignacionPlanDTO> plan = solucion.getAsignaciones().stream()
                .map(asignacion -> {
                    Envio envio = asignacion.getEnvio();
                    Itinerario itinerario = asignacion.getItinerario();

                    if (itinerario == null || itinerario.getVuelos() == null || itinerario.getVuelos().isEmpty()) {
                        return new AsignacionPlanDTO(
                                envio.getIdPedido(), envio.getOrigenIata(), envio.getDestinoIata(),
                                envio.getCantidadMaletas(), null, null, null, null, null, null, null, "SIN_ITINERARIO_ASIGNADO"
                        );
                    }

                    VueloInstanciado primerVuelo = itinerario.getVuelos().get(0);
                    VueloInstanciado ultimoVuelo = itinerario.getVuelos().get(itinerario.getVuelos().size() - 1);
                    String estado = itinerario.getCantidadVuelos() == 1 ? "ASIGNADO_DIRECTO" : "ASIGNADO_CON_ESCALA_" + itinerario.getCantidadVuelos() + "_VUELOS";

                    return new AsignacionPlanDTO(
                            envio.getIdPedido(), envio.getOrigenIata(), envio.getDestinoIata(), envio.getCantidadMaletas(),
                            primerVuelo.getCodigoBase(), primerVuelo.getOrigenIata(), ultimoVuelo.getDestinoIata(),
                            primerVuelo.getFechaHoraSalida().toString(),
                            ultimoVuelo.getFechaHoraLlegada().toString(),
                            primerVuelo.getFechaHoraSalidaUtc().toString(),
                            ultimoVuelo.getFechaHoraLlegadaUtc().toString(),
                            estado
                    );
                })
                .toList();

        return new PlanResultadoDTO(algoritmo.toUpperCase(), solucion.getFitness(), solucion.getAsignaciones().size(), plan);
    }

    public ResultadoSimulacionDTO ejecutarSimulacion(LocalDate fechaInicio, int cantidadDias) {
        if (cantidadDias <= 0) throw new IllegalArgumentException("La cantidad de días debe ser mayor que 0.");

        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(cantidadDias).atStartOfDay();

        List<Envio> envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        List<Vuelo> vuelosBase = vueloRepository.findAll();
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();


        /*
            List<VueloInstanciado> vuelosInstanciados = generarVuelosInstanciados(vuelosBase, fechaInicio, cantidadDias);
            Le estoy agregando 2 días más para que las maletas al final del rango seleccionado tengan una planificación,
            he verificado que si pones cantidadDias nada más salen 3-4 maletas sin poder planificar porque ya no hay vuelos
            siguientes porque los vuelos instanciados acaban ahí.
         */
        long inicioGeneracionVuelos = System.currentTimeMillis();
        List<VueloInstanciado> vuelosInstanciados = generarVuelosInstanciados(vuelosBase, fechaInicio, cantidadDias, aeropuertos);
        long tiempoGeneracionVuelos = System.currentTimeMillis() - inicioGeneracionVuelos;

        long inicioGeneracionItinerarios = System.currentTimeMillis();
        Map<String, List<Itinerario>> itinerariosPorRuta = itinerarioService.generarItinerariosPorRuta(vuelosInstanciados);
        long tiempoGeneracionItinerarios = System.currentTimeMillis() - inicioGeneracionItinerarios;

        System.out.println("========================================");
        System.out.println("VENTANA DE SIMULACIÓN");
        System.out.println("Inicio: " + inicio);
        System.out.println("Fin   : " + fin);
        System.out.println("Días simulados: " + cantidadDias);
        System.out.println("Envios usados: " + envios.size());
        System.out.println("========================================");

        // --- GRASP ---
        System.out.println("\n=== EJECUTANDO GRASP ===");
        long inicioGrasp = System.currentTimeMillis();
        SolucionRuta solucionGrasp = graspSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        long tiempoGrasp = System.currentTimeMillis() - inicioGrasp;
        imprimirMetricasPlanificacion("GRASP", fechaInicio, cantidadDias, envios, vuelosBase, vuelosInstanciados,
                aeropuertos, itinerariosPorRuta, tiempoGeneracionVuelos, tiempoGeneracionItinerarios,
                tiempoGrasp, solucionGrasp);

        double entregaPromGrasp = calcularTiempoEntregaPromedio(solucionGrasp);
        double vuelosPromGrasp = calcularVuelosPromedio(solucionGrasp, envios.size());

        System.out.println("[RESULTADO GRASP]");
        System.out.println("Factor 1 (Tiempo ejecución ms): " + tiempoGrasp);
        System.out.println("Factor 2 (Entrega promedio hr): " + entregaPromGrasp);
        System.out.println("Factor 3 (Vuelos promedio): " + vuelosPromGrasp);
        System.out.println("Fitness: " + solucionGrasp.getFitness());
        System.out.println("SLA Incumplidos: " + solucionGrasp.getExcedeSlaCount());
        System.out.println("Sin Itinerario: " + solucionGrasp.getSinItinerarioCount() + "/" + envios.size());

        solucionGrasp.getAsignaciones().stream()
                .filter(a -> a.getItinerario() == null)
                .forEach(a -> {
                    Envio e = a.getEnvio();
                    System.out.println("  [SIN_ITINERARIO GRASP] idPedido=" + e.getIdPedido()
                            + " ruta=" + e.getOrigenIata() + "->" + e.getDestinoIata()
                            + " maletas=" + e.getCantidadMaletas()
                            + " fechaHora=" + e.getFechaHora());
                });

        // --- TABU ---
        System.out.println("\n=== EJECUTANDO TABU ===");
        long inicioTabu = System.currentTimeMillis();
        SolucionRuta solucionTabu = tabuSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        long tiempoTabu = System.currentTimeMillis() - inicioTabu;
        imprimirMetricasPlanificacion("TABU", fechaInicio, cantidadDias, envios, vuelosBase, vuelosInstanciados,
                aeropuertos, itinerariosPorRuta, tiempoGeneracionVuelos, tiempoGeneracionItinerarios,
                tiempoTabu, solucionTabu);

        double entregaPromTabu = calcularTiempoEntregaPromedio(solucionTabu);
        double vuelosPromTabu = calcularVuelosPromedio(solucionTabu, envios.size());

        System.out.println("[RESULTADO TABU]");
        System.out.println("Factor 1 (Tiempo ejecución ms): " + tiempoTabu);
        System.out.println("Factor 2 (Entrega promedio hr): " + entregaPromTabu);
        System.out.println("Factor 3 (Vuelos promedio): " + vuelosPromTabu);
        System.out.println("Fitness: " + solucionTabu.getFitness());
        System.out.println("SLA Incumplidos: " + solucionTabu.getExcedeSlaCount());
        System.out.println("Sin Itinerario: " + solucionTabu.getSinItinerarioCount() + "/" + envios.size());
        solucionTabu.getAsignaciones().stream()
                .filter(a -> a.getItinerario() == null)
                .forEach(a -> {
                    Envio e = a.getEnvio();
                    System.out.println("  [SIN_ITINERARIO TABU]  idPedido=" + e.getIdPedido()
                            + " ruta=" + e.getOrigenIata() + "->" + e.getDestinoIata()
                            + " maletas=" + e.getCantidadMaletas()
                            + " fechaHora=" + e.getFechaHora());
                });

        String ganador = solucionGrasp.getFitness() < solucionTabu.getFitness() ? "GRASP" : (solucionTabu.getFitness() < solucionGrasp.getFitness() ? "TABU" : "EMPATE");

        return new ResultadoSimulacionDTO(
                inicio.toString(),
                fin.toString(),
                envios.size(),
                vuelosBase.size(),
                vuelosInstanciados.size(),
                aeropuertos.size(),

                // GRASP
                solucionGrasp.getFitness(),
                solucionGrasp.getAsignaciones().size(),
                tiempoGrasp,
                vuelosPromGrasp,
                solucionGrasp.getSinItinerarioCount(),
                solucionGrasp.getExcedeSlaCount(),

                // TABU
                solucionTabu.getFitness(),
                solucionTabu.getAsignaciones().size(),
                tiempoTabu,
                vuelosPromTabu,
                solucionTabu.getSinItinerarioCount(),
                solucionTabu.getExcedeSlaCount(),

                ganador
        );
    }

    private List<VueloInstanciado> generarVuelosInstanciados(
            List<Vuelo> vuelosBase,
            LocalDate fechaInicio,
            int cantidadDias,
            List<Aeropuerto> aeropuertos
    ) {
        List<VueloInstanciado> vuelosInstanciados = new ArrayList<>();
        for (int i = 0; i < cantidadDias; i++) {
            vuelosInstanciados.addAll(vueloFactory.crearInstanciasDelDia(vuelosBase, fechaInicio.plusDays(i), aeropuertos));
        }
        return vuelosInstanciados;
    }

    private double calcularVuelosPromedio(SolucionRuta solucion, int totalEnvios) {
        if (totalEnvios == 0) return 0.0;
        int totalVuelosUsados = solucion.getAsignaciones().stream()
                .filter(a -> a.getItinerario() != null)
                .mapToInt(a -> a.getItinerario().getCantidadVuelos()).sum();
        return (double) totalVuelosUsados / totalEnvios;
    }

    private double calcularTiempoEntregaPromedio(SolucionRuta solucion) {
        long asignados = solucion.getAsignaciones().stream().filter(a -> a.getItinerario() != null).count();
        if (asignados == 0) return 0.0;
        double sumaHoras = solucion.getAsignaciones().stream()
                .filter(a -> a.getItinerario() != null)
                .mapToDouble(a -> PlanificadorUtils.calcularDuracionItinerarioHoras(a.getItinerario()))
                .sum();
        return sumaHoras / asignados;
    }
    public SolucionRuta calcularSolucion(String algoritmo, LocalDate fechaInicio, int dias) {
        if (dias <= 0) throw new IllegalArgumentException("La cantidad de días debe ser mayor que 0.");

        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(dias).atStartOfDay();

        List<Envio> envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        List<Vuelo> vuelosBase = vueloRepository.findAll();
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();

        long inicioGeneracionVuelos = System.currentTimeMillis();
        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, fechaInicio, dias, aeropuertos);
        long tiempoGeneracionVuelos = System.currentTimeMillis() - inicioGeneracionVuelos;

        long inicioGeneracionItinerarios = System.currentTimeMillis();
        Map<String, List<Itinerario>> itinerariosPorRuta =
                itinerarioService.generarItinerariosPorRuta(vuelosInstanciados);
        long tiempoGeneracionItinerarios = System.currentTimeMillis() - inicioGeneracionItinerarios;

        long inicioAlgoritmo = System.currentTimeMillis();
        SolucionRuta solucion = algoritmo.equalsIgnoreCase("TABU")
                ? tabuSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos)
                : graspSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        long tiempoAlgoritmo = System.currentTimeMillis() - inicioAlgoritmo;

        imprimirMetricasPlanificacion(algoritmo, fechaInicio, dias, envios, vuelosBase, vuelosInstanciados,
                aeropuertos, itinerariosPorRuta, tiempoGeneracionVuelos, tiempoGeneracionItinerarios,
                tiempoAlgoritmo, solucion);

        return solucion;
    }

    public List<Envio> obtenerEnviosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        return envioDataStore.obtenerEnviosEnVentana(inicio, fin);
    }

    public SolucionRuta calcularSolucionParaEnvios(String algoritmo, LocalDate fechaInicio, int dias, List<Envio> envios) {
        if (dias <= 0) throw new IllegalArgumentException("La cantidad de dias debe ser mayor que 0.");

        List<Vuelo> vuelosBase = vueloRepository.findAll();
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();

        long inicioGeneracionVuelos = System.currentTimeMillis();
        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, fechaInicio, dias, aeropuertos);
        long tiempoGeneracionVuelos = System.currentTimeMillis() - inicioGeneracionVuelos;

        long inicioGeneracionItinerarios = System.currentTimeMillis();
        Map<String, List<Itinerario>> itinerariosPorRuta =
                itinerarioService.generarItinerariosPorRuta(vuelosInstanciados);
        long tiempoGeneracionItinerarios = System.currentTimeMillis() - inicioGeneracionItinerarios;

        long inicioAlgoritmo = System.currentTimeMillis();
        SolucionRuta solucion = algoritmo.equalsIgnoreCase("TABU")
                ? tabuSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos)
                : graspSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        long tiempoAlgoritmo = System.currentTimeMillis() - inicioAlgoritmo;

        imprimirMetricasPlanificacion(algoritmo, fechaInicio, dias, envios, vuelosBase, vuelosInstanciados,
                aeropuertos, itinerariosPorRuta, tiempoGeneracionVuelos, tiempoGeneracionItinerarios,
                tiempoAlgoritmo, solucion);

        return solucion;
    }

    private void imprimirMetricasPlanificacion(
            String algoritmo,
            LocalDate fechaInicio,
            int dias,
            List<Envio> envios,
            List<Vuelo> vuelosBase,
            List<VueloInstanciado> vuelosInstanciados,
            List<Aeropuerto> aeropuertos,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            long tiempoGeneracionVuelos,
            long tiempoGeneracionItinerarios,
            long tiempoAlgoritmo,
            SolucionRuta solucion
    ) {
        int totalItinerarios = itinerariosPorRuta.values().stream().mapToInt(List::size).sum();
        int rutasConItinerarios = itinerariosPorRuta.size();
        double promedioItinerariosPorRuta = rutasConItinerarios > 0
                ? totalItinerarios / (double) rutasConItinerarios
                : 0.0;

        System.out.println("[METRICA PLANIFICACION] algoritmo=" + algoritmo.toUpperCase()
                + " fechaInicio=" + fechaInicio
                + " dias=" + dias
                + " enviosProcesados=" + envios.size()
                + " maletasProcesadas=" + sumarMaletas(envios)
                + " vuelosBase=" + vuelosBase.size()
                + " vuelosInstanciados=" + vuelosInstanciados.size()
                + " aeropuertos=" + aeropuertos.size()
                + " rutasConItinerarios=" + rutasConItinerarios
                + " totalItinerarios=" + totalItinerarios
                + " promedioItinerariosPorRuta=" + promedioItinerariosPorRuta
                + " tiempoGeneracionVuelosMs=" + tiempoGeneracionVuelos
                + " tiempoGeneracionItinerariosMs=" + tiempoGeneracionItinerarios
                + " tiempoAlgoritmoMs=" + tiempoAlgoritmo
                + " fitnessFinal=" + solucion.getFitness()
                + " sinItinerarioCount=" + solucion.getSinItinerarioCount()
                + " excedeSlaCount=" + solucion.getExcedeSlaCount());
    }

    private int sumarMaletas(List<Envio> envios) {
        return envios.stream().mapToInt(Envio::getCantidadMaletas).sum();
    }

    public void ejecutarPrueba() {
        LocalDate fechaInicio = LocalDate.of(2026, 2, 25);
        int cantidadDias = 5;
        int iteraciones = 15;

        System.out.println("========================================");
        System.out.println("INICIANDO EXPERIMENTACIÓN (" + iteraciones + " iteraciones)");
        System.out.println("========================================");

        double sumaFitGrasp = 0, sumaFitTabu = 0;
        long sumaTimeGrasp = 0, sumaTimeTabu = 0;
        int winsGrasp = 0, winsTabu = 0;

        for (int i = 1; i <= iteraciones; i++) {
            System.out.println("\n----------------------------------------");
            System.out.println("ITERACIÓN " + i);
            System.out.println("----------------------------------------");

            ResultadoSimulacionDTO r = ejecutarSimulacion(fechaInicio, cantidadDias);

            sumaFitGrasp += r.getFitnessGrasp();
            sumaFitTabu += r.getFitnessTabu();
            sumaTimeGrasp += r.getTiempoGrasp();
            sumaTimeTabu += r.getTiempoTabu();

            if (r.getGanador().equals("GRASP")) winsGrasp++;
            else if (r.getGanador().equals("TABU")) winsTabu++;
        }

        System.out.println("\n========================================");
        System.out.println("RESUMEN FINAL");
        System.out.println("========================================");
        System.out.println("GRASP Wins: " + winsGrasp + " | TABU Wins: " + winsTabu);
        System.out.println("Promedio Fitness GRASP: " + (sumaFitGrasp / iteraciones));
        System.out.println("Promedio Fitness TABU : " + (sumaFitTabu / iteraciones));
        System.out.println("Promedio Tiempo GRASP : " + (sumaTimeGrasp / iteraciones) + " ms");
        System.out.println("Promedio Tiempo TABU  : " + (sumaTimeTabu / iteraciones) + " ms");
        System.out.println("========================================");
    }
}
