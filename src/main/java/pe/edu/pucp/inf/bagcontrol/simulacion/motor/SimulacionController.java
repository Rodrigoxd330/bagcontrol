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

    @PostMapping("/preparar")
    public RespuestaInicioSimulacionDTO preparaSimulacion(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaInicio,

            @RequestParam(value = "fechaFin", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin,

            @RequestParam(value = "k", defaultValue = "15") int k,

            @RequestParam(value = "algoritmo", defaultValue = "TABU") String algoritmo
    ) {
        String simulacionId = simulacionManager.crearJob(fechaInicio, fechaFin, k, algoritmo);
        String modo = (fechaFin == null) ? MODO_COLAPSO : MODO_NORMAL;
        return crearRespuestaInicio(simulacionId, modo);
    }

    @PostMapping("/iniciar/{simulacionId}/arrancar")
    public Map<String, String> arrancarSimulacion(@PathVariable String simulacionId) {
        simulacionManager.arrancarJob(simulacionId);
        return Map.of("mensaje", "Simulacion en marcha");
    }

    @PostMapping("/iniciar")
    public RespuestaInicioSimulacionDTO iniciarSimulacion(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaInicio,

            @RequestParam(value = "fechaFin", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin,

            @RequestParam(value = "k", defaultValue = "15") int k,

            @RequestParam(value = "algoritmo", defaultValue = "TABU") String algoritmo,

            @RequestParam(value = "modo", required = false) String modo
    ) {
        if (MODO_COLAPSO.equalsIgnoreCase(modo)) {
            return iniciarColapso(fechaInicio, k, algoritmo);
        }

        LocalDate fechaFinNormalizada = fechaFin != null ? fechaFin : fechaInicio.plusDays(1);
        return iniciarNormal(fechaInicio, fechaFinNormalizada, k, algoritmo);
    }

    @PostMapping("/ws/iniciar")
    public RespuestaInicioSimulacionDTO iniciarSimulacionWs(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaInicio,

            @RequestParam(value = "fechaFin", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin,

            @RequestParam(value = "k", defaultValue = "15") int k,

            @RequestParam(value = "algoritmo", defaultValue = "TABU") String algoritmo,

            @RequestParam(value = "modo", required = false) String modo
    ) {
        return iniciarSimulacion(fechaInicio, fechaFin, k, algoritmo, modo);
    }

    @PostMapping("/ws/iniciar-colapso")
    public RespuestaInicioSimulacionDTO iniciarSimulacionColapsoWs(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaInicio,

            @RequestParam(value = "k", defaultValue = "15") int k,

            @RequestParam(value = "algoritmo", defaultValue = "TABU") String algoritmo
    ) {
        return iniciarColapso(fechaInicio, k, algoritmo);
    }

    private RespuestaInicioSimulacionDTO iniciarNormal(LocalDate fechaInicio, LocalDate fechaFin, int k, String algoritmo) {
        String simulacionId = simulacionManager.crearYArrancarJob(fechaInicio, fechaFin, k, algoritmo);
        return crearRespuestaInicio(simulacionId, MODO_NORMAL);
    }

    private RespuestaInicioSimulacionDTO iniciarColapso(LocalDate fechaInicio, int k, String algoritmo) {
        String simulacionId = simulacionManager.crearYArrancarJobColapso(fechaInicio, k, algoritmo);
        return crearRespuestaInicio(simulacionId, MODO_COLAPSO);
    }

    private RespuestaInicioSimulacionDTO crearRespuestaInicio(String simulacionId, String modo) {
        String topic = "/topic/simulacion/" + simulacionId + "/eventos";
        return new RespuestaInicioSimulacionDTO(simulacionId, topic, topic, modo);
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

    @PostMapping("/{simulacionId}/velocidad")
    public Map<String, String> cambiarVelocidad(
            @PathVariable String simulacionId,
            @RequestParam("multiplicador") int multiplicador
    ) {
        simulacionManager.cambiarVelocidad(simulacionId, multiplicador);
        return Map.of("mensaje", "Velocidad actualizada a " + multiplicador + "x");
    }

    @GetMapping("/{simulacionId}/estado")
    public SimulacionEstadoDTO obtenerEstado(@PathVariable String simulacionId) {
        return simulacionManager.obtenerEstado(simulacionId);
    }

    @GetMapping("/{simulacionId}/vuelos/{codigoVuelo}/envios")
    public List<EnvioDTO> obtenerEnviosPorVuelo(
            @PathVariable String simulacionId,
            @PathVariable Long codigoVuelo
    ) {
        return simulacionManager.extraerEnviosPorVuelo(simulacionId, codigoVuelo);
    }
}
