package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EventoVueloDTO extends EventoBaseDTO {
    private Long codigoVuelo;
    private String origenIata;
    private String destinoIata;
    private String estado;
    private int cantidadMaletas;
    private String horaSalida;
    private String horaLlegada;
    private String horaSalidaLocal;
    private String horaLlegadaLocal;
    private String horaSalidaUtc;
    private String horaLlegadaUtc;
}
