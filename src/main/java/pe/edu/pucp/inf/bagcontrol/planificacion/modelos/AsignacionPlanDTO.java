package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AsignacionPlanDTO {
    private String idPedido;
    private String origenEnvio;
    private String destinoEnvio;
    private int cantidadMaletas;

    private Long codigoVuelo;
    private String origenVuelo;
    private String destinoVuelo;
    private String horaSalida;
    private String horaLlegada;

    private String estado;
}