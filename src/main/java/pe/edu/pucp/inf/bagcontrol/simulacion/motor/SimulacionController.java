package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionEstadoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.out.RespuestaInicioSimulacionDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/simulacion")
@RequiredArgsConstructor
public class SimulacionController {

    private static final String MODO_COLAPSO = "COLAPSO";
    private static final String MODO_NORMAL = "NORMAL";

    private final SimulacionManager simulacionManager;

    /*
    * Crear el hilo (sin ejecutarlo) para que esté 'listo para arrncar'
    * devuelve el id de la simulación
     */
    @PostMapping("/preparar")
    public RespuestaInicioSimulacionDTO preparaSimulacion(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaInicio,

            @RequestParam(value = "fechaFin", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin,

            @RequestParam(value = "k", defaultValue = "15") int k,

            @RequestParam(value = "algoritmo", defaultValue = "TABU") String algoritmo
    ) {
        System.out.println("FRONTEND MANDÓ: " + fechaFin);
        String simulacionId = simulacionManager.crearJob(fechaInicio, fechaFin, k, algoritmo);
        String modo = (fechaFin == null) ? MODO_COLAPSO : MODO_NORMAL;
        String topic = "/topic/simulacion/" + simulacionId + "/eventos";
        return new RespuestaInicioSimulacionDTO(simulacionId, topic, modo);
    }

    /*
    * Solamante arranca la simulación del id que le pasemos
     */
    @PostMapping("/iniciar/{simulacionId}/arrancar")
    public Map<String, String> arrancarSimulacion(@PathVariable String simulacionId) {
        simulacionManager.arrancarJob(simulacionId);
        return Map.of("mensaje", "Simulacion en marcha");
    }


    @PostMapping("/{simulacionId}/pausar")
    public Map<String, String> pausar(@PathVariable String simulacionId) {
        simulacionManager.pausarJob(simulacionId);
        return Map.of("estado", "PAUSADA", "mensaje", "Simulacion pausada");
    }

    @PostMapping("/{simulacionId}/reanudar")
    public Map<String, String> reanudar(@PathVariable String simulacionId) {
        simulacionManager.reanudarJob(simulacionId);
        return Map.of("estado", "EN_PROCESO", "mensaje", "Simulacion reanudada");
    }

    @PostMapping("/{simulacionId}/detener")
    public Map<String, String> detener(@PathVariable String simulacionId) {
        simulacionManager.detenerJob(simulacionId);
        return Map.of("estado", "DETENIDA", "mensaje", "Simulacion abortada");
    }

    @GetMapping("/{simulacionId}/estado")
    public SimulacionEstadoDTO obtenerEstado(@PathVariable String simulacionId) {
        return simulacionManager.obtenerEstado(simulacionId);
    }

    /*
     * Puede servir para después
    */
    @GetMapping("/{simulacionId}/vuelos/{codigoVuelo}/envios")
    public List<EnvioDTO> obtenerEnviosPorVuelo(
            @PathVariable String simulacionId,
            @PathVariable Long codigoVuelo
    ) {
        return simulacionManager.extraerEnviosPorVuelo(simulacionId, codigoVuelo);
    }
}
