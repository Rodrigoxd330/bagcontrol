package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import com.fasterxml.jackson.annotation.JsonAlias;
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
    @JsonAlias("fechaHoraRegistro")
    private String fechaHora;
    private boolean esOperacionDia;
}
