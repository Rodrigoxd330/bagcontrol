package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RutaAsignada {
    private Envio envio;
    private Itinerario itinerario;
}