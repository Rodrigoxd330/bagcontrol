package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.incidencias.Incidencia;
import pe.edu.pucp.inf.bagcontrol.entidades.incidencias.IncidenciaRepository;
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
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PlanificadorService {

    private final EnvioDataStore envioDataStore;
    private final VueloRepository vueloRepository;
    private final IncidenciaRepository incidenciaRepository;
    private final AeropuertoRepository aeropuertoRepository;
    private final VueloFactory vueloFactory;
    private final GRASPSearch graspSearch;
    private final TabuSearch tabuSearch;
    private final ItinerarioService itinerarioService;

    public PlanResultadoDTO obtenerPlan(String algoritmo, LocalDate fechaInicio, int dias) {
        if (dias <= 0) throw new IllegalArgumentException("La cantidad de días debe ser mayor que 0.");

        long inicioTotal = System.currentTimeMillis();
        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(dias).atStartOfDay();

        long inicioCargaEnvios = System.currentTimeMillis();
        List<Envio> envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        registrarEnviosEnVentana(inicio, fin, envios);
        long tiempoCargaEnvios = System.currentTimeMillis() - inicioCargaEnvios;
        List<Vuelo> vuelosBase = vueloRepository.findAll();
        registrarVuelosBase(vuelosBase);
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();

        // Se mantiene el margen de 2 días para cubrir el SLA máximo de 48h [cite: 12]
        long inicioGeneracionVuelos = System.currentTimeMillis();
        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, fechaInicio, dias + 2, aeropuertos);
        aplicarIncidencias(vuelosInstanciados);
        long tiempoGeneracionVuelos = System.currentTimeMillis() - inicioGeneracionVuelos;

        long inicioGeneracionItinerarios = System.currentTimeMillis();
        Map<String, List<Itinerario>> itinerariosPorRuta =
                itinerarioService.generarItinerariosPorRuta(vuelosInstanciados);
        long tiempoGeneracionItinerarios = System.currentTimeMillis() - inicioGeneracionItinerarios;

        long inicioAlgoritmo = System.currentTimeMillis();
        SolucionRuta solucion = algoritmo.equalsIgnoreCase("TABU")
                ? tabuSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos)
                : graspSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        registrarUsoVuelosCrud(vuelosBase, solucion);
        long tiempoAlgoritmo = System.currentTimeMillis() - inicioAlgoritmo;

        imprimirMetricasPlanificacion(algoritmo, fechaInicio, dias, envios, vuelosBase, vuelosInstanciados,
                aeropuertos, itinerariosPorRuta, tiempoGeneracionVuelos, tiempoGeneracionItinerarios,
                tiempoAlgoritmo, solucion, tiempoCargaEnvios, System.currentTimeMillis() - inicioTotal);

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

        return new PlanResultadoDTO(
                algoritmo.toUpperCase(),
                solucion.getFitness(),
                solucion.getAsignaciones().size(),
                plan,
                envios.size(),
                sumarMaletas(envios),
                vuelosInstanciados.size(),
                contarItinerarios(itinerariosPorRuta),
                solucion.getSinItinerarioCount(),
                solucion.getExcedeSlaCount(),
                contarVuelosCancelados(vuelosInstanciados),
                solucion.getVuelosCanceladosUsadosCount(),
                tiempoCargaEnvios,
                tiempoGeneracionVuelos,
                tiempoGeneracionItinerarios,
                tiempoAlgoritmo,
                System.currentTimeMillis() - inicioTotal
        );
    }

    public ResultadoSimulacionDTO ejecutarSimulacion(LocalDate fechaInicio, int cantidadDias) {
        if (cantidadDias <= 0) throw new IllegalArgumentException("La cantidad de días debe ser mayor que 0.");

        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(cantidadDias).atStartOfDay();

        List<Envio> envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        registrarEnviosEnVentana(inicio, fin, envios);
        List<Vuelo> vuelosBase = vueloRepository.findAll();
        registrarVuelosBase(vuelosBase);
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();


        /*
            List<VueloInstanciado> vuelosInstanciados = generarVuelosInstanciados(vuelosBase, fechaInicio, cantidadDias);
            Le estoy agregando 2 días más para que las maletas al final del rango seleccionado tengan una planificación,
            he verificado que si pones cantidadDias nada más salen 3-4 maletas sin poder planificar porque ya no hay vuelos
            siguientes porque los vuelos instanciados acaban ahí.
         */
        long inicioGeneracionVuelos = System.currentTimeMillis();
        List<VueloInstanciado> vuelosInstanciados = generarVuelosInstanciados(vuelosBase, fechaInicio, cantidadDias + 2, aeropuertos);
        aplicarIncidencias(vuelosInstanciados);
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
        registrarUsoVuelosCrud(vuelosBase, solucionGrasp);
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
        registrarUsoVuelosCrud(vuelosBase, solucionTabu);
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

    public SolucionRuta calcularSolucion(
            String algoritmo,
            LocalDateTime inicio,
            LocalDateTime fin,
            List<Envio> pendientes
    ) {
        return calcularSolucion(algoritmo, inicio, fin, pendientes, Map.of());
    }

    public SolucionRuta calcularSolucion(
            String algoritmo,
            LocalDateTime inicio,
            LocalDateTime fin,
            List<Envio> pendientes,
            Map<String, Integer> inventarioActual
    ) {
        if (!fin.isAfter(inicio)) throw new IllegalArgumentException("La fecha fin debe ser mayor a la inicio.");

        long inicioTotal = System.currentTimeMillis();
        long inicioCargaEnvios = System.currentTimeMillis();
        List<Envio> enviosVentana = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        registrarEnviosEnVentana(inicio, fin, enviosVentana);
        long tiempoCargaEnvios = System.currentTimeMillis() - inicioCargaEnvios;

        // Unimos los nuevos de la ventana con los pendientes que vienen del State
        List<Envio> todosLosEnvios = new ArrayList<>(enviosVentana);
        if (pendientes != null && !pendientes.isEmpty()) {
            todosLosEnvios.addAll(pendientes);
        }

        List<Vuelo> vuelosBase = vueloRepository.findAll();
        registrarVuelosBase(vuelosBase);
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();

        long inicioGeneracionVuelos = System.currentTimeMillis();

        int diasGeneracion = calcularDiasGeneracion(inicio, fin);
        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, inicio.toLocalDate(), diasGeneracion, aeropuertos);
        aplicarIncidencias(vuelosInstanciados);

        long tiempoGeneracionVuelos = System.currentTimeMillis() - inicioGeneracionVuelos;
        long inicioGeneracionItinerarios = System.currentTimeMillis();

        Map<String, List<Itinerario>> itinerariosPorRuta = itinerarioService.generarItinerariosPorRuta(vuelosInstanciados);

        long tiempoGeneracionItinerarios = System.currentTimeMillis() - inicioGeneracionItinerarios;
        long inicioAlgoritmo = System.currentTimeMillis();

        // IMPORTANTE: Pasamos 'todosLosEnvios' al algoritmo en lugar de solo los de la ventana
        Map<String, Integer> inventarioInicial = new java.util.HashMap<>(inventarioActual);
        Set<String> enviosNuevos = enviosVentana.stream()
                .map(Envio::getIdPedido)
                .collect(java.util.stream.Collectors.toSet());
        SolucionRuta solucion = algoritmo.equalsIgnoreCase("TABU")
                ? tabuSearch.ejecutar(todosLosEnvios, itinerariosPorRuta, aeropuertos, inventarioInicial, enviosNuevos)
                : graspSearch.ejecutar(todosLosEnvios, itinerariosPorRuta, aeropuertos);

        registrarUsoVuelosCrud(vuelosBase, solucion);
        long tiempoAlgoritmo = System.currentTimeMillis() - inicioAlgoritmo;
        imprimirMetricasPlanificacion(algoritmo, inicio.toLocalDate(), diasGeneracion, todosLosEnvios, vuelosBase, vuelosInstanciados,
                aeropuertos, itinerariosPorRuta, tiempoGeneracionVuelos, tiempoGeneracionItinerarios,
                tiempoAlgoritmo, solucion);
        System.out.println("[SIM5D-BLOQUE-PERFORMANCE] ventanaInicio=" + inicio
                + " ventanaFin=" + fin
                + " algoritmo=" + algoritmo.toUpperCase()
                + " enviosNuevos=" + enviosVentana.size()
                + " enviosPendientesEntrada=" + (pendientes == null ? 0 : pendientes.size())
                + " aeropuertosSinCapacidad=" + contarAeropuertosSinCapacidad(inventarioInicial, aeropuertos)
                + " cargaEnviosMs=" + tiempoCargaEnvios
                + " generacionVuelosMs=" + tiempoGeneracionVuelos
                + " vuelosCanceladosDetectados=" + contarVuelosCancelados(vuelosInstanciados)
                + " generacionItinerariosMs=" + tiempoGeneracionItinerarios
                + " tabuOAlgoritmoMs=" + tiempoAlgoritmo
                + " vuelosCanceladosUsados=" + solucion.getVuelosCanceladosUsadosCount()
                + " totalMs=" + (System.currentTimeMillis() - inicioTotal));

        return solucion;
    }

    public List<VueloInstanciado> obtenerVuelosCanceladosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        List<Vuelo> vuelosBase = vueloRepository.findAll();
        registrarVuelosBase(vuelosBase);
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();
        int dias = calcularDiasGeneracion(inicio, fin);
        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, inicio.toLocalDate(), dias, aeropuertos);
        aplicarIncidencias(vuelosInstanciados);

        Set<String> vistos = new HashSet<>();
        var inicioUtc = inicio.toInstant(java.time.ZoneOffset.UTC);
        var finUtc = fin.toInstant(java.time.ZoneOffset.UTC);
        return vuelosInstanciados.stream()
                .filter(VueloInstanciado::isEstaCancelado)
                .filter(v -> !v.getFechaHoraSalidaUtc().isBefore(inicioUtc) && v.getFechaHoraSalidaUtc().isBefore(finUtc))
                .filter(v -> vistos.add(v.getCodigoBase() + "@" + v.getFechaHoraSalida()))
                .toList();
    }



    public List<Envio> obtenerEnviosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        return envioDataStore.obtenerEnviosEnVentana(inicio, fin);
    }

    private void registrarEnviosEnVentana(LocalDateTime inicio, LocalDateTime fin, List<Envio> envios) {
        Set<String> idsIncluidos = envios.stream()
                .map(Envio::getIdPedido)
                .collect(java.util.stream.Collectors.toSet());
        System.out.println("[PLANIFICADOR] envíos en ventana=" + envios.size()
                + " inicio=" + inicio + " fin=" + fin);
        for (String idPedido : envioDataStore.obtenerIdsCrudActivos()) {
            System.out.println("[PLANIFICADOR] envío creado por CRUD incluido="
                    + idsIncluidos.contains(idPedido) + " id=" + idPedido);
        }
    }

    private void registrarVuelosBase(List<Vuelo> vuelosBase) {
        System.out.println("[PLANIFICADOR] vuelosBase=" + vuelosBase.size());
        vuelosBase.stream()
                .filter(Vuelo::isCreadoPorCrud)
                .forEach(vuelo -> System.out.println(
                        "[PLANIFICADOR] vueloCrudIncluido=true id=" + vuelo.getCodigo()
                ));
    }

    private void registrarUsoVuelosCrud(List<Vuelo> vuelosBase, SolucionRuta solucion) {
        Set<Long> vuelosUsados = solucion.getAsignaciones().stream()
                .filter(asignacion -> asignacion.getItinerario() != null)
                .flatMap(asignacion -> asignacion.getItinerario().getVuelos().stream())
                .map(VueloInstanciado::getCodigoBase)
                .collect(java.util.stream.Collectors.toSet());
        vuelosBase.stream()
                .filter(Vuelo::isCreadoPorCrud)
                .forEach(vuelo -> System.out.println(
                        "[ITINERARIO] vueloCrudUsado=" + vuelosUsados.contains(vuelo.getCodigo())
                                + " id=" + vuelo.getCodigo()
                ));
    }

    public SolucionRuta calcularSolucionParaEnvios(String algoritmo, LocalDate fechaInicio, int dias, List<Envio> envios) {
        if (dias <= 0) throw new IllegalArgumentException("La cantidad de dias debe ser mayor que 0.");

        List<Vuelo> vuelosBase = vueloRepository.findAll();
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();

        long inicioGeneracionVuelos = System.currentTimeMillis();
        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, fechaInicio, dias + 2, aeropuertos);
        aplicarIncidencias(vuelosInstanciados);
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
            SolucionRuta solucion,
            long tiempoCargaEnvios,
            long tiempoTotalPlanificacion
    ) {
        imprimirMetricasPlanificacion(algoritmo, fechaInicio, dias, envios, vuelosBase, vuelosInstanciados,
                aeropuertos, itinerariosPorRuta, tiempoGeneracionVuelos, tiempoGeneracionItinerarios,
                tiempoAlgoritmo, solucion);
        System.out.println("[PLANIFICACION-METRICA-SALIDA] totalEnviosVentana=" + envios.size()
                + " totalMaletasVentana=" + sumarMaletas(envios)
                + " totalVuelosInstanciados=" + vuelosInstanciados.size()
                + " totalItinerariosGenerados=" + contarItinerarios(itinerariosPorRuta)
                + " totalAsignaciones=" + solucion.getAsignaciones().size()
                + " enviosSinItinerario=" + solucion.getSinItinerarioCount()
                + " enviosSlaIncumplido=" + solucion.getExcedeSlaCount()
                + " vuelosCanceladosDetectados=" + contarVuelosCancelados(vuelosInstanciados)
                + " vuelosCanceladosUsadosEnSolucion=" + solucion.getVuelosCanceladosUsadosCount()
                + " tiempoCargaEnviosMs=" + tiempoCargaEnvios
                + " tiempoGeneracionVuelosMs=" + tiempoGeneracionVuelos
                + " tiempoGeneracionItinerariosMs=" + tiempoGeneracionItinerarios
                + " tiempoAlgoritmoMs=" + tiempoAlgoritmo
                + " tiempoTotalPlanificacionMs=" + tiempoTotalPlanificacion
                + " fitness=" + solucion.getFitness());
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

        System.out.println("[PLANIFICACION-MÉTRICA] algoritmo=" + algoritmo.toUpperCase()
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

    private int contarItinerarios(Map<String, List<Itinerario>> itinerariosPorRuta) {
        return itinerariosPorRuta.values().stream().mapToInt(List::size).sum();
    }

    private int contarVuelosCancelados(List<VueloInstanciado> vuelosInstanciados) {
        return (int) vuelosInstanciados.stream().filter(VueloInstanciado::isEstaCancelado).count();
    }

    private int contarAeropuertosSinCapacidad(Map<String, Integer> inventario, List<Aeropuerto> aeropuertos) {
        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(java.util.stream.Collectors.toMap(Aeropuerto::getCodigoIata, aeropuerto -> aeropuerto));
        return (int) inventario.entrySet().stream()
                .filter(entry -> {
                    Aeropuerto aeropuerto = mapaAeropuertos.get(entry.getKey());
                    return aeropuerto != null && entry.getValue() >= aeropuerto.getCapacidadAlmacen();
                })
                .count();
    }

    private int calcularDiasGeneracion(LocalDateTime inicio, LocalDateTime fin) {
        long minutos = java.time.Duration.between(inicio, fin).toMinutes();
        int diasVentana = (int) Math.ceil(Math.max(minutos, 1) / 1440.0);
        return Math.max(diasVentana, 1) + 2;
    }

    private void aplicarIncidencias(List<VueloInstanciado> vuelosInstanciados) {
        List<Incidencia> incidencias = incidenciaRepository.findAll();
        for (VueloInstanciado vuelo : vuelosInstanciados) {
            if (vuelo.isEstaCancelado()) {
                if (vuelo.getMotivoCancelacion() == null) {
                    vuelo.setMotivoCancelacion("VUELO_CANCELADO");
                }
                continue;
            }

            for (Incidencia incidencia : incidencias) {
                if (incidencia.getFechaHora() == null || incidencia.getOrigenIata() == null) {
                    continue;
                }
                LocalDateTime inicio = incidencia.getFechaHora();
                LocalDateTime fin = inicio.plusMinutes(Math.max(incidencia.getTiempoRecuperacionMinutos(), 0));

                boolean bloqueoSalida = incidencia.isNoPuedeEnviar()
                        && incidencia.getOrigenIata().equalsIgnoreCase(vuelo.getOrigenIata())
                        && estaDentroDeIncidencia(vuelo.getFechaHoraSalida(), inicio, fin);
                boolean bloqueoLlegada = incidencia.isNoPuedeRecibir()
                        && incidencia.getOrigenIata().equalsIgnoreCase(vuelo.getDestinoIata())
                        && estaDentroDeIncidencia(vuelo.getFechaHoraLlegada(), inicio, fin);

                if (bloqueoSalida || bloqueoLlegada) {
                    vuelo.setCanceladoPorIncidencia(true);
                    vuelo.setMotivoCancelacion(construirMotivoIncidencia(incidencia, bloqueoSalida, bloqueoLlegada));
                    break;
                }
            }
        }
    }

    private boolean estaDentroDeIncidencia(LocalDateTime fechaHora, LocalDateTime inicio, LocalDateTime fin) {
        if (fin.isEqual(inicio)) {
            return fechaHora.isEqual(inicio);
        }
        return !fechaHora.isBefore(inicio) && fechaHora.isBefore(fin);
    }

    private String construirMotivoIncidencia(Incidencia incidencia, boolean bloqueoSalida, boolean bloqueoLlegada) {
        String tipo = bloqueoSalida ? "AEROPUERTO_NO_PUEDE_ENVIAR" : "AEROPUERTO_NO_PUEDE_RECIBIR";
        if (bloqueoSalida && bloqueoLlegada) {
            tipo = "AEROPUERTO_BLOQUEADO";
        }
        String descripcion = incidencia.getDescripcion();
        return descripcion == null || descripcion.isBlank() ? tipo : tipo + ": " + descripcion;
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
