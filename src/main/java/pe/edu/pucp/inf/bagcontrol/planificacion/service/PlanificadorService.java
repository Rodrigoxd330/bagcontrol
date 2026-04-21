package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo.GRASPSearch;
import pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo.TabuSearch;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PlanificadorService {

    private final EnvioDataStore envioDataStore;
    private final VueloRepository vueloRepository;
    private final AeropuertoRepository aeropuertoRepository;
    private final GRASPSearch graspSearch;
    private final TabuSearch tabuSearch;

    public void ejecutarPrueba() {

        LocalDateTime inicio = LocalDateTime.of(2026, 2, 2, 0, 0);
        LocalDateTime fin = inicio.plusHours(6);

        var envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);
        var vuelos = vueloRepository.findAll();
        var aeropuertos = aeropuertoRepository.findAll();

        System.out.println("========================================");
        System.out.println("VENTANA DE SIMULACIÓN");
        System.out.println("Inicio: " + inicio);
        System.out.println("Fin   : " + fin);
        System.out.println("Envios usados: " + envios.size());
        System.out.println("Vuelos disponibles: " + vuelos.size());
        System.out.println("Aeropuertos cargados: " + aeropuertos.size());
        System.out.println("========================================");

        System.out.println("\n=== EJECUTANDO GRASP ===");
        var solucionGrasp = graspSearch.ejecutar(envios, vuelos, aeropuertos);
        System.out.println("Fitness GRASP: " + solucionGrasp.getFitness());
        System.out.println("Asignaciones GRASP: " + solucionGrasp.getAsignaciones().size());

        System.out.println("\n=== EJECUTANDO TABU ===");
        var solucionTabu = tabuSearch.ejecutar(envios, vuelos, aeropuertos);
        System.out.println("Fitness TABU: " + solucionTabu.getFitness());
        System.out.println("Asignaciones TABU: " + solucionTabu.getAsignaciones().size());

        System.out.println("\n=== COMPARACIÓN FINAL ===");
        if (solucionGrasp.getFitness() < solucionTabu.getFitness()) {
            System.out.println("GRASP obtuvo mejor fitness.");
        } else if (solucionTabu.getFitness() < solucionGrasp.getFitness()) {
            System.out.println("TABU obtuvo mejor fitness.");
        } else {
            System.out.println("Ambos algoritmos obtuvieron el mismo fitness.");
        }
    }
}