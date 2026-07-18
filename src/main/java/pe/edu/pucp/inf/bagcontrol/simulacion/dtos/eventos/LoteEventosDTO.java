package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoteEventosDTO {
    private String simulacionId;
    private Long numeroLote;
    private String ventanaInicio;
    private String ventanaFin;
    private int cantidadEventos;
    private List<EventoBaseDTO> eventos;
    private List<EnvioDTO> envios;
    private int saMs;
    /** Version del plan; permite descartar futuros invalidados sin romper clientes anteriores. */
    private Long versionPlan;
    /** Indice exclusivo de bloques fisicos. Es null para mensajes de control. */
    private Long indiceFisico;

    public LoteEventosDTO(
            String simulacionId, Long numeroLote, String ventanaInicio, String ventanaFin,
            int cantidadEventos, List<EventoBaseDTO> eventos, List<EnvioDTO> envios, int saMs
    ) {
        this(simulacionId, numeroLote, ventanaInicio, ventanaFin, cantidadEventos,
                eventos, envios, saMs, null, null);
    }
}
