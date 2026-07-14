package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

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
    private long ultimoLoteEmitido;
    private String algoritmo;
    private int k;
    private String fechaInicio;
    private String fechaCreacion;
    private String tiempoSimuladoActual;
    private String fechaHoraInicioReal;
    private String fechaHoraFinReal;
}
