package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnvioAlmacenDTO {
    private EnvioDTO envio;
    private String codigoAeropuerto;
    private String tipoAlmacen;
    private String estadoEnvio;
}
