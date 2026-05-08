package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Movimiento {
    private Envio envio;
    private Itinerario itinerarioAnterior;
    private Itinerario itinerarioNuevo;

    public String getIdMovimientoTabu() {
        String idEnvio = envio != null ? envio.getIdPedido() : "SIN_ENVIO";
        String anterior = itinerarioAnterior != null ? itinerarioAnterior.getIdItinerario() : "SIN_ANTERIOR";
        String nuevo = itinerarioNuevo != null ? itinerarioNuevo.getIdItinerario() : "SIN_NUEVO";

        return idEnvio + "-" + anterior + "-" + nuevo;
    }
}