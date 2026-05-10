package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
