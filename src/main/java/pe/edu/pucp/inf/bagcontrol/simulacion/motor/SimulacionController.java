package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioAlmacenDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioRutaDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionEstadoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.out.RespuestaInicioSimulacionDTO;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
            @RequestParam("fechaInicio") String fechaInicio,
            @RequestParam(value = "fechaFin", required = false) String fechaFin,
            @RequestParam(value = "k", defaultValue = "30") int k,
            @RequestParam(value = "algoritmo", defaultValue = "TABU") String algoritmo
    ) {
        System.out.println("FRONTEND MANDÓ FECHA Inicio: " + fechaInicio);

        // El helper ahora devuelve el LocalDateTime correcto interpretando el estándar internacional
        LocalDateTime inicio = parseFechaHoraFlexible(fechaInicio);
        LocalDateTime fin = parseFechaHoraFlexible(fechaFin);

        String simulacionId = simulacionManager.crearJob(inicio, fin, k, algoritmo);
        String modo = (fin == null) ? MODO_COLAPSO : MODO_NORMAL;
        String topic = "/topic/simulacion/" + simulacionId + "/eventos";

        return new RespuestaInicioSimulacionDTO(simulacionId, topic, modo);
    }

    private LocalDateTime parseFechaHoraFlexible(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }

        String limpio = valor.trim();
        try {
            // Si el string contiene la 'Z' o un desvío de zona horaria, es un formato internacional válido.
            // Lo parseamos como Instant y lo convertimos a LocalDateTime en la línea temporal de UTC.
            if (limpio.contains("Z") || limpio.contains("z") || limpio.contains("+") || (limpio.lastIndexOf("-") > 10)) {
                return LocalDateTime.ofInstant(Instant.parse(limpio), ZoneOffset.UTC);
            }

            // Fallback: Si por alguna razón llega un formato local puro (ej. YYYY-MM-DDTHH:mm), se usa el parse estándar
            return LocalDateTime.parse(limpio);
        } catch (DateTimeParseException e) {
            try {
                // Segundo fallback: Si solo enviaron la fecha plana (ej. YYYY-MM-DD), se asume el inicio del día
                return java.time.LocalDate.parse(limpio).atStartOfDay();
            } catch (DateTimeParseException ex) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "No se pudo parsear la estructura de fecha provista: " + valor
                );
            }
        }
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
        List<EnvioDTO> envios = simulacionManager.extraerEnviosPorVuelo(simulacionId, codigoVuelo);
        System.out.println("Envios: ");
        for(EnvioDTO envio : envios){
            System.out.println(envio.getIdPedido());
        }
        return envios;
    }

    @GetMapping("/{simulacionId}/envios/{idPedido}/ruta")
    public EnvioRutaDTO obtenerRutaEnvio(
            @PathVariable String simulacionId,
            @PathVariable String idPedido
    ) {
        return simulacionManager.obtenerRutaEnvio(simulacionId, idPedido);
    }

    @GetMapping("/{simulacionId}/aeropuertos/{codigoIata}/envios")
    public List<EnvioAlmacenDTO> obtenerEnviosPorAlmacen(
            @PathVariable String simulacionId,
            @PathVariable String codigoIata
    ) {
        return simulacionManager.obtenerEnviosPorAlmacen(simulacionId, codigoIata);
    }
}
