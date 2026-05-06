package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EventoIncidenciaDTO extends EventoBaseDTO {
    private Long codigoVuelo;
    private String estado;
    private int maletasAfectadas;
}
