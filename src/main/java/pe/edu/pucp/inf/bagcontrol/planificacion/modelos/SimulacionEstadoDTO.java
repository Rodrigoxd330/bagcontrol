package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SimulacionEstadoDTO {

    private String simulacionId;
    private String estado;
    private boolean pausada;
    private boolean detenida;
    private long saMs;
    private long ultimoEventoEmitido;
    private String algoritmo;
    private int k;
    private String fechaInicio;
    private String fechaCreacion;
}