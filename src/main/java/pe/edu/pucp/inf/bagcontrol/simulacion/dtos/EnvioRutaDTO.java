package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnvioRutaDTO {
    private EnvioDTO envio;
    private String estado;
    private String aeropuertoActual;
    private String idItinerario;
    private List<EscalaRutaDTO> escalas;
}
