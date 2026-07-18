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
}
