package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${simulacion.planificacion.timeout-ms:28000}")
    private long planificacionTimeoutMs = 28_000L;

    @Value("${simulacion.planificacion.tabu.iteraciones:120}")
    private int tabuIteraciones = 120;

    @Value("${simulacion.planificacion.tabu.tenure:12}")
    private int tabuTenure = 12;

    @Value("${simulacion.planificacion.tabu.max-vecinos:50}")
    private int tabuMaxVecinos = 50;

    @Value("${simulacion.planificacion.margen-capacidad:0.05}")
    private double margenCapacidad = 0.05;

    private final EnvioDataStore envioDataStore;
    private final VueloRepository vueloRepository;
    private final IncidenciaRepository incidenciaRepository;
    private final AeropuertoRepository aeropuertoRepository;
    private final VueloFactory vueloFactory;
    private final GRASPSearch graspSearch;
    private final TabuSearch tabuSearch;
    private final ItinerarioService itinerarioService;

    public void precargarEnvios(LocalDateTime ventanaInicio){
        envioDataStore.firstPopulateEnvios(ventanaInicio);
    }

    public Set<String> obtenerIdsEnviosCrudActivos() {
        return envioDataStore.obtenerIdsCrudActivos();
    }

    public int contarEnviosCrudActivos() {
        return envioDataStore.contarEnviosCrudActivos();
    }

    public long contarVuelosBase() {
        return vueloRepository.count();
    }

    public List<Vuelo> obtenerVuelosBaseSnapshot() {
        return vueloRepository.findAll();
    }

    public List<Aeropuerto> obtenerAeropuertosSnapshot() {
        return aeropuertoRepository.findAll();
    }

    public List<Incidencia> obtenerIncidenciasSnapshot() {
        return incidenciaRepository.findAll();
    }

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
        // --- GRASP ---
        long inicioGrasp = System.currentTimeMillis();
        SolucionRuta solucionGrasp = graspSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        registrarUsoVuelosCrud(vuelosBase, solucionGrasp);
        long tiempoGrasp = System.currentTimeMillis() - inicioGrasp;
        double entregaPromGrasp = calcularTiempoEntregaPromedio(solucionGrasp);
        double vuelosPromGrasp = calcularVuelosPromedio(solucionGrasp, envios.size());

        // --- TABU ---
        long inicioTabu = System.currentTimeMillis();
        SolucionRuta solucionTabu = tabuSearch.ejecutar(envios, itinerariosPorRuta, aeropuertos);
        registrarUsoVuelosCrud(vuelosBase, solucionTabu);
        long tiempoTabu = System.currentTimeMillis() - inicioTabu;
        double entregaPromTabu = calcularTiempoEntregaPromedio(solucionTabu);
        double vuelosPromTabu = calcularVuelosPromedio(solucionTabu, envios.size());

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
        return calcularSolucionDesdeFuente(
                algoritmo,
                inicio,
                fin,
                pendientes,
                inventarioActual,
                envioDataStore.obtenerEnviosEnVentana(inicio, fin),
                "ZIP"
        );
    }

    public List<Envio> obtenerEnviosOperacionDiaEnVentana(
            LocalDateTime inicio,
            LocalDateTime fin
    ) {
        return envioDataStore.obtenerEnviosCrudEnVentana(inicio, fin);
    }

    public SolucionRuta calcularSolucionOperacionDia(
            String algoritmo,
            LocalDateTime inicio,
            LocalDateTime fin,
            List<Envio> pendientes,
            Map<String, Integer> inventarioActual,
            List<Envio> enviosVentana
    ) {
        return calcularSolucionDesdeFuente(
                algoritmo,
                inicio,
                fin,
                pendientes,
                inventarioActual,
                enviosVentana,
                "CRUD_OPERATIVO"
        );
    }

    public SolucionRuta calcularSolucionOperacionDia(
            String algoritmo,
            LocalDateTime inicio,
            LocalDateTime fin,
            List<Envio> pendientes,
            Map<String, Integer> inventarioActual,
            List<Envio> enviosVentana,
            List<Vuelo> vuelosBaseSnapshot,
            List<Aeropuerto> aeropuertosSnapshot,
            List<Incidencia> incidenciasSnapshot
    ) {
        return calcularSolucionDesdeFuente(
                algoritmo,
                inicio,
                fin,
                pendientes,
                inventarioActual,
                enviosVentana,
                "CRUD_OPERATIVO",
                vuelosBaseSnapshot,
                aeropuertosSnapshot,
                incidenciasSnapshot
        );
    }

    public SolucionRuta calcularSolucion(
            String algoritmo,
            LocalDateTime inicio,
            LocalDateTime fin,
            List<Envio> pendientes,
            Map<String, Integer> inventarioActual,
            List<Vuelo> vuelosBaseSnapshot,
            List<Aeropuerto> aeropuertosSnapshot,
            List<Incidencia> incidenciasSnapshot
    ) {
        List<Envio> enviosVentana = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        return calcularSolucionDesdeFuente(
                algoritmo,
                inicio,
                fin,
                pendientes,
                inventarioActual,
                enviosVentana,
                "ZIP",
                vuelosBaseSnapshot,
                aeropuertosSnapshot,
                incidenciasSnapshot
        );
    }

    private SolucionRuta calcularSolucionDesdeFuente(
            String algoritmo,
            LocalDateTime inicio,
            LocalDateTime fin,
            List<Envio> pendientes,
            Map<String, Integer> inventarioActual,
            List<Envio> enviosVentana,
            String fuenteEnvios
    ) {
        return calcularSolucionDesdeFuente(
                algoritmo, inicio, fin, pendientes, inventarioActual, enviosVentana, fuenteEnvios,
                planificacionTimeoutMs
        );
    }

    private SolucionRuta calcularSolucionDesdeFuente(
            String algoritmo,
            LocalDateTime inicio,
            LocalDateTime fin,
            List<Envio> pendientes,
            Map<String, Integer> inventarioActual,
            List<Envio> enviosVentana,
            String fuenteEnvios,
            long presupuestoMs
    ) {
        return calcularSolucionDesdeFuente(
                algoritmo,
                inicio,
                fin,
                pendientes,
                inventarioActual,
                enviosVentana,
                fuenteEnvios,
                vueloRepository.findAll(),
                aeropuertoRepository.findAll(),
                incidenciaRepository.findAll(),
                presupuestoMs
        );
    }

    private SolucionRuta calcularSolucionDesdeFuente(
            String algoritmo,
            LocalDateTime inicio,
            LocalDateTime fin,
            List<Envio> pendientes,
            Map<String, Integer> inventarioActual,
            List<Envio> enviosVentana,
            String fuenteEnvios,
            List<Vuelo> vuelosBaseSnapshot,
            List<Aeropuerto> aeropuertosSnapshot,
            List<Incidencia> incidenciasSnapshot
    ) {
        return calcularSolucionDesdeFuente(
                algoritmo, inicio, fin, pendientes, inventarioActual, enviosVentana, fuenteEnvios,
                vuelosBaseSnapshot, aeropuertosSnapshot, incidenciasSnapshot, planificacionTimeoutMs
        );
    }

    private SolucionRuta calcularSolucionDesdeFuente(
            String algoritmo,
            LocalDateTime inicio,
            LocalDateTime fin,
            List<Envio> pendientes,
            Map<String, Integer> inventarioActual,
            List<Envio> enviosVentana,
            String fuenteEnvios,
            List<Vuelo> vuelosBaseSnapshot,
            List<Aeropuerto> aeropuertosSnapshot,
            List<Incidencia> incidenciasSnapshot,
            long presupuestoMs
    ) {
        if (!fin.isAfter(inicio)) throw new IllegalArgumentException("La fecha fin debe ser mayor a la inicio.");

        long inicioTotal = System.currentTimeMillis();
        long presupuestoEfectivoMs = Math.max(1_000L, Math.min(planificacionTimeoutMs, presupuestoMs));
        long deadlinePlanificacionMs = inicioTotal + presupuestoEfectivoMs;
        long inicioCargaEnvios = System.currentTimeMillis();
        registrarEnviosEnVentana(inicio, fin, enviosVentana);
        long tiempoCargaEnvios = System.currentTimeMillis() - inicioCargaEnvios;

        // Unimos los nuevos de la ventana con los pendientes que vienen del State
        List<Envio> todosLosEnvios = new ArrayList<>(enviosVentana);
        if (pendientes != null && !pendientes.isEmpty()) {
            todosLosEnvios.addAll(pendientes);
        }
        List<Vuelo> vuelosBase = new ArrayList<>(vuelosBaseSnapshot);
        registrarVuelosBase(vuelosBase);
        List<Aeropuerto> aeropuertos = new ArrayList<>(aeropuertosSnapshot);

        long inicioGeneracionVuelos = System.currentTimeMillis();
        int diasGeneracion = calcularDiasGeneracion(inicio, fin);
        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, inicio.toLocalDate(), diasGeneracion, aeropuertos);
        aplicarIncidencias(vuelosInstanciados, incidenciasSnapshot);

        long tiempoGeneracionVuelos = System.currentTimeMillis() - inicioGeneracionVuelos;
        long inicioGeneracionItinerarios = System.currentTimeMillis();

        Map<String, List<Itinerario>> itinerariosPorRuta = itinerarioService.generarItinerariosPorRuta(vuelosInstanciados);

        long tiempoGeneracionItinerarios = System.currentTimeMillis() - inicioGeneracionItinerarios;
        long inicioAlgoritmo = System.currentTimeMillis();

        // IMPORTANTE: Pasamos 'todosLosEnvios' al algoritmo en lugar de solo los de la ventana
        Map<String, Integer> inventarioInicial = new java.util.HashMap<>(inventarioActual);
        reservarMargenCapacidad(inventarioInicial, aeropuertos);
        Set<String> enviosNuevos = new HashSet<>();
        enviosVentana.stream().map(Envio::getIdPedido).forEach(enviosNuevos::add);
        if (pendientes != null) {
            pendientes.stream().map(Envio::getIdPedido).forEach(enviosNuevos::add);
        }
        SolucionRuta solucion = algoritmo.equalsIgnoreCase("TABU")
                ? tabuSearch.ejecutar(todosLosEnvios, itinerariosPorRuta, aeropuertos, inventarioInicial,
                        enviosNuevos, tabuIteraciones, tabuTenure, tabuMaxVecinos,
                        deadlinePlanificacionMs, presupuestoEfectivoMs)
                : graspSearch.ejecutar(todosLosEnvios, itinerariosPorRuta, aeropuertos);

        registrarUsoVuelosCrud(vuelosBase, solucion);
        long tiempoAlgoritmo = System.currentTimeMillis() - inicioAlgoritmo;

        return solucion;
    }

    public List<VueloInstanciado> obtenerVuelosCanceladosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        List<Vuelo> vuelosBase = vueloRepository.findAll();
        registrarVuelosBase(vuelosBase);
        List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();
        return obtenerVuelosCanceladosEnVentana(
                inicio,
                fin,
                vuelosBase,
                aeropuertos,
                incidenciaRepository.findAll()
        );
    }

    public List<VueloInstanciado> obtenerVuelosCanceladosEnVentana(
            LocalDateTime inicio,
            LocalDateTime fin,
            List<Vuelo> vuelosBase,
            List<Aeropuerto> aeropuertos,
            List<Incidencia> incidencias
    ) {
        registrarVuelosBase(vuelosBase);
        int dias = calcularDiasGeneracion(inicio, fin);
        List<VueloInstanciado> vuelosInstanciados =
                generarVuelosInstanciados(vuelosBase, inicio.toLocalDate(), dias, aeropuertos);
        aplicarIncidencias(vuelosInstanciados, incidencias);

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
    }

    private void registrarVuelosBase(List<Vuelo> vuelosBase) {
    }

    private void registrarUsoVuelosCrud(List<Vuelo> vuelosBase, SolucionRuta solucion) {
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

        return solucion;
    }

    private void reservarMargenCapacidad(Map<String, Integer> inventario, List<Aeropuerto> aeropuertos) {
        if (margenCapacidad <= 0.0) {
            return;
        }
        for (Aeropuerto aeropuerto : aeropuertos) {
            int reserva = (int) Math.ceil(aeropuerto.getCapacidadAlmacen() * margenCapacidad);
            inventario.merge(aeropuerto.getCodigoIata(), reserva, Integer::sum);
        }
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
        aplicarIncidencias(vuelosInstanciados, incidenciaRepository.findAll());
    }

    private void aplicarIncidencias(List<VueloInstanciado> vuelosInstanciados, List<Incidencia> incidencias) {
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
        double sumaFitGrasp = 0, sumaFitTabu = 0;
        long sumaTimeGrasp = 0, sumaTimeTabu = 0;
        int winsGrasp = 0, winsTabu = 0;

        for (int i = 1; i <= iteraciones; i++) {
            ResultadoSimulacionDTO r = ejecutarSimulacion(fechaInicio, cantidadDias);

            sumaFitGrasp += r.getFitnessGrasp();
            sumaFitTabu += r.getFitnessTabu();
            sumaTimeGrasp += r.getTiempoGrasp();
            sumaTimeTabu += r.getTiempoTabu();

            if (r.getGanador().equals("GRASP")) winsGrasp++;
            else if (r.getGanador().equals("TABU")) winsTabu++;
        }
    }
}
