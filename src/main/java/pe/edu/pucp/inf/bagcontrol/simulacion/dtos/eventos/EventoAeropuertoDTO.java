package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EventoAeropuertoDTO extends EventoBaseDTO {
    private String codigoAeropuerto;
    private String estado;
    private String mensaje;
    private double porcentajeOcupacion;
    private int maletasActuales;
    private int capacidadAlmacen;
}
