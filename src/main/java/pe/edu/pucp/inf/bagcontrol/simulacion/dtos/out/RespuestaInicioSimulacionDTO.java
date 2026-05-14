package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.out;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RespuestaInicioSimulacionDTO {
    private String simulacionId;
    private String websocketTopic;
    private String modo;
}
