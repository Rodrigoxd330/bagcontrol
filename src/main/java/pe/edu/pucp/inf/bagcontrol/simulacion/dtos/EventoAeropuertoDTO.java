package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EventoAeropuertoDTO extends EventoBaseDTO {
    private String codigoAeropuerto;
    private String estado;
    private double porcentajeOcupacion;
    private int maletasActuales;
    private int capacidadAlmacen;
}
