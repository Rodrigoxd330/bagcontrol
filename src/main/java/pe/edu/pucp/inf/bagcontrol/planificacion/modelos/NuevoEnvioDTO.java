package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NuevoEnvioDTO {
    private String origenIata;
    private String destinoIata;
    private int cantidadMaletas;
    private String idCliente;
    private String fechaHora;
}
