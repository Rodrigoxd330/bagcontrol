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
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, fechaInicio, dias + 2);

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
                                envio.getIdPedido(), envio.getOrigenIata(), envio.getDestinoIata(),
                                envio.getCantidadMaletas(), null, null, null, null, null, "SIN_ITINERARIO_ASIGNADO"
                        );
                    }

                    VueloInstanciado primerVuelo = itinerario.getVuelos().get(0);
                    VueloInstanciado ultimoVuelo = itinerario.getVuelos().get(itinerario.getVuelos().size() - 1);
                    String estado = itinerario.getCantidadVuelos() == 1 ? "ASIGNADO_DIRECTO" : "ASIGNADO_CON_ESCALA_" + itinerario.getCantidadVuelos() + "_VUELOS";

                    return new AsignacionPlanDTO(
                            envio.getIdPedido(), envio.getOrigenIata(), envio.getDestinoIata(), envio.getCantidadMaletas(),
                            primerVuelo.getCodigoBase(), primerVuelo.getOrigenIata(), ultimoVuelo.getDestinoIata(),
                            primerVuelo.getFechaHoraSalida().toString(), ultimoVuelo.getFechaHoraLlegada().toString(), estado
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
        List<VueloInstanciado> vuelosInstanciados = generarVuelosInstanciados(vuelosBase, fechaInicio, cantidadDias + 2);

        Map<String, List<Itinerario>> itinerariosPorRuta = itinerarioService.generarItinerariosPorRuta(vuelosInstanciados);

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

        double entregaPromGrasp = calcularTiempoEntregaPromedio(solucionGrasp);
        double vuelosPromGrasp = calcularVuelosPromedio(solucionGrasp, envios.size());

        System.out.println("[RESULTADO GRASP]");
        System.out.println("Factor 1 (Tiempo ejecución ms): " + tiempoGrasp);
        System.out.println("Factor 2 (Entrega promedio hr): " + entregaPromGrasp);
        System.out.println("Factor 3 (Vuelos promedio): " + vuelosPromGrasp);
        System.out.println("Fitness: " + solucionGrasp.getFitness());
        System.out.println("SLA Incumplidos: " + solucionGrasp.getExcedeSlaCount());
        System.out.println("Sin Itinerario: " + solucionGrasp.getSinItinerarioCount() + "/" + envios.size());

        // --- TABU ---
        System.out.println("\n=== EJECUTANDO TABU ===");
        long inicioTabu = System.currentTimeMillis();
        SolucionRuta solucionTabu = tabuSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        long tiempoTabu = System.currentTimeMillis() - inicioTabu;

        double entregaPromTabu = calcularTiempoEntregaPromedio(solucionTabu);
        double vuelosPromTabu = calcularVuelosPromedio(solucionTabu, envios.size());

        System.out.println("[RESULTADO TABU]");
        System.out.println("Factor 1 (Tiempo ejecución ms): " + tiempoTabu);
        System.out.println("Factor 2 (Entrega promedio hr): " + entregaPromTabu);
        System.out.println("Factor 3 (Vuelos promedio): " + vuelosPromTabu);
        System.out.println("Fitness: " + solucionTabu.getFitness());
        System.out.println("SLA Incumplidos: " + solucionTabu.getExcedeSlaCount());
        System.out.println("Sin Itinerario: " + solucionTabu.getSinItinerarioCount() + "/" + envios.size());

        String ganador = solucionGrasp.getFitness() < solucionTabu.getFitness() ? "GRASP" : (solucionTabu.getFitness() < solucionGrasp.getFitness() ? "TABU" : "EMPATE");

        return new ResultadoSimulacionDTO(
                inicio.toString(), fin.toString(), envios.size(), vuelosBase.size(), vuelosInstanciados.size(), aeropuertos.size(),
                solucionGrasp.getFitness(), solucionGrasp.getAsignaciones().size(), tiempoGrasp, vuelosPromGrasp,
                solucionTabu.getFitness(), solucionTabu.getAsignaciones().size(), tiempoTabu, vuelosPromTabu,
                ganador
        );
    }

    private List<VueloInstanciado> generarVuelosInstanciados(List<Vuelo> vuelosBase, LocalDate fechaInicio, int cantidadDias) {
        List<VueloInstanciado> vuelosInstanciados = new ArrayList<>();
        for (int i = 0; i < cantidadDias; i++) {
            vuelosInstanciados.addAll(vueloFactory.crearInstanciasDelDia(vuelosBase, fechaInicio.plusDays(i)));
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

    public void ejecutarPrueba() {
        LocalDate fechaInicio = LocalDate.of(2026, 2, 2);
        int cantidadDias = 3;
        int iteraciones = 3;

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
    public void optimizarParametros(){
        //Random search
        Random rand = new Random();

        //Parametros hardcodeados
        LocalDate fechaInicio = LocalDate.of(2026, 2, 2);
        int cantidadDias = 3;
        int iteraciones = 50;
        int algo_iter = 1;

        //Valores ganadores (GRASP)
        double bestFitness = Double.MAX_VALUE;
        int bestIteraciones = 0;
        int bestMaxVecinos = 0;
        double bestAlpha = 0.0;

        //Valores ganadores (TABU)
        double bestTabuFitness = Double.MAX_VALUE;
        int bestTabuIteraciones = 0;
        int bestTabuMaxVecinos = 0;
        int bestTabuTenure = 0;

        System.out.println("\n========================================");
        System.out.println("OPTIMIZACION DE HIPERPARAMETROS");
        System.out.println("========================================");

        for(int i=0;i<iteraciones;i++){
            //Usando Randomized Search porque explora mas valores
            //Random da distribucion uniforme, pero para hiperparametros es mejor log-uniforme
            //debido a que en esta distribucion la probabilidad es igual independientemente del tamaño del rango de parámetros

            //GRASP
            int _iteraciones = (int)Math.round(30.0*Math.pow(100/30.0,rand.nextDouble()));
            int _maxVecinos = (int)Math.round(30.0*Math.pow(10/30.0,rand.nextDouble()));
            double _alpha = Math.pow(2,rand.nextDouble())/2.0;

            //TABU
            int _tabuIteraciones = (int)Math.round(30.0*Math.pow(150/30.0,rand.nextDouble()));
            int _tabuMaxVecinos = (int)Math.round(30.0*Math.pow(10/30.0,rand.nextDouble()));
            int _tabuTenure = (int)Math.round(10.0*Math.pow(100/10.0,rand.nextDouble()));

            System.out.println("========================================");
            System.out.println("ITERACION "+(i+1));
            System.out.println("GRASP:");
            System.out.println("Num. Iteraciones:  "+_iteraciones);
            System.out.println("Max. Vecinos:  "+_maxVecinos);
            System.out.println("Alfa:  "+_alpha);
            System.out.println("Tabu Search:");
            System.out.println("Num. Iteraciones:  "+_tabuIteraciones);
            System.out.println("Max. Vecinos:  "+_tabuMaxVecinos);
            System.out.println("Tenure:  "+_tabuTenure);
            GRASPSearch.establecerParametros(_iteraciones,_maxVecinos,_alpha);
            TabuSearch.establecerParametros(_tabuIteraciones,_tabuMaxVecinos,_tabuTenure);
            double sumaFit = 0;
            double sumaFitTabu = 0;
            //long sumaTime = 0;

            for(int j=0;j<algo_iter;j++){
                ResultadoSimulacionDTO r = ejecutarSimulacion(fechaInicio, cantidadDias);
                //sumaTime += r.getTiempoGrasp();
                sumaFit += r.getFitnessGrasp();
                sumaFitTabu += r.getFitnessTabu();
            }
            double promFit = sumaFit/algo_iter;
            if(promFit<=bestFitness){
                bestFitness = promFit;
                bestIteraciones = _iteraciones;
                bestMaxVecinos = _maxVecinos;
                bestAlpha = _alpha;
            }
            double promTabu = sumaFitTabu/algo_iter;
            if(promTabu<=bestTabuFitness){
                bestTabuFitness = promTabu;
                bestTabuIteraciones = _tabuIteraciones;
                bestTabuMaxVecinos = _tabuMaxVecinos;
                bestTabuTenure = _tabuTenure;
            }
        }

        System.out.println("\n========================================");
        System.out.println("RESULTADOS: ");
        System.out.println("\n========================================");
        System.out.println("GRASP: ");
        System.out.println("Mejor Fitness: "+ bestFitness);
        System.out.println("Mejor num. iteraciones: " + bestIteraciones);
        System.out.println("Mejor max. vecinos: " + bestMaxVecinos);
        System.out.println("Mejor alfa: " + bestAlpha);
        System.out.println("\n========================================");
        System.out.println("Tabu Search: ");
        System.out.println("Mejor Fitness: "+ bestTabuFitness);
        System.out.println("Mejor num. iteraciones: " + bestTabuIteraciones);
        System.out.println("Mejor max. vecinos: " + bestTabuMaxVecinos);
        System.out.println("Mejor tenure: " + bestTabuTenure);
        System.out.println("\n========================================");
        System.out.println("Ganador: " + (bestFitness<bestTabuFitness?"GRASP Search":"Tabu Search"));

    }
}