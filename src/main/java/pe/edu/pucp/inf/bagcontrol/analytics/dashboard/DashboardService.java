package pe.edu.pucp.inf.bagcontrol.analytics.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloFactory;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo.GRASPSearch;
import pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo.TabuSearch;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final EnvioDataStore envioDataStore;
    private final VueloRepository vueloRepository;
    private final AeropuertoRepository aeropuertoRepository;
    private final VueloFactory vueloFactory;
    private final GRASPSearch graspSearch;
    private final TabuSearch tabuSearch;

    public DashboardResumenDTO obtenerResumenDashboard(LocalDate fechaInicio, int dias, String algoritmo) {

//        var inicio = fechaInicio.atStartOfDay();
//        var fin = fechaInicio.plusDays(dias).atStartOfDay();
//
//        var envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
//        var vuelosBase = vueloRepository.findAll();
//        var aeropuertos = aeropuertoRepository.findAll();
//
//        var vuelosInstanciados = new ArrayList<VueloInstanciado>();
//        for (int i = 0; i < dias; i++) {
//            vuelosInstanciados.addAll(vueloFactory.crearInstanciasDelDia(vuelosBase, fechaInicio.plusDays(i)));
//        }
//
//        var solucion = algoritmo.equalsIgnoreCase("TABU")
//                ? tabuSearch.ejecutar(envios, vuelosInstanciados, aeropuertos)
//                : graspSearch.ejecutar(envios, vuelosInstanciados, aeropuertos);
//
//        int enviosEntregadosATiempo = 0;
//        double totalHorasAlmacenaje = 0.0;
//        int enviosConVuelo = 0;
//
//        Map<String, Integer> cargaPorVuelo = new HashMap<>();
//        Map<String, Integer> capacidadPorVuelo = new HashMap<>();
//
//        for (var asignacion : solucion.getAsignaciones()) {
//            var envio = asignacion.getEnvio();
//            var vuelo = asignacion.getVuelo();
//
//            if (vuelo == null) continue;
//
//            enviosConVuelo++;
//
//            boolean fueraDePlazo = PlanificadorUtils.excedePlazoMaximo(envio, vuelo, aeropuertos);
//            if (!fueraDePlazo) {
//                enviosEntregadosATiempo++;
//            }
//
//            double horasAlmacenaje = Duration.between(envio.getFechaHora(), vuelo.getFechaHoraSalida()).toMinutes() / 60.0;
//            if (horasAlmacenaje >= 0) {
//                totalHorasAlmacenaje += horasAlmacenaje;
//            }
//
//            String claveVuelo = vuelo.getCodigoBase() + "-" + vuelo.getFechaHoraSalida();
//            cargaPorVuelo.merge(claveVuelo, envio.getCantidadMaletas(), Integer::sum);
//            capacidadPorVuelo.put(claveVuelo, vuelo.getCapacidadMax());
//        }
//
//        double tiempoPromedioAlmacenaje = enviosConVuelo == 0 ? 0.0 : totalHorasAlmacenaje / enviosConVuelo;
//
//        int cargaTotal = cargaPorVuelo.values().stream().mapToInt(Integer::intValue).sum();
//        int capacidadTotal = capacidadPorVuelo.values().stream().mapToInt(Integer::intValue).sum();
//
//        double promedioPorcentajeOcupacion = capacidadTotal == 0 ? 0.0 : (cargaTotal * 100.0) / capacidadTotal;
//
//        double costoPorAnho = calcularCostoEstimado(cargaTotal, capacidadPorVuelo.size());
//
//        return new DashboardResumenDTO(
//                costoPorAnho,
//                0,
//                enviosEntregadosATiempo,
//                tiempoPromedioAlmacenaje,
//                promedioPorcentajeOcupacion,
//                "OPERATIVO"
//        );
        return new DashboardResumenDTO();
    }

    private double calcularCostoEstimado(int totalMaletas, int vuelosUsados) {
        return (totalMaletas * 10.0) + (vuelosUsados * 100.0);
    }
}