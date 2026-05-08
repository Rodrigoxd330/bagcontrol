package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnvioDTO {
    private String idPedido;
    private String origenIata;
    private String destinoIata;
    private String fechaHora;
    private int cantidadMaletas;
    private String idCliente;
}