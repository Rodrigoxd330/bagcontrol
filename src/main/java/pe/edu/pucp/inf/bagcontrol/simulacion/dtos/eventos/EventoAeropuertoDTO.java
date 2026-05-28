package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EventoAeropuertoDTO extends EventoBaseDTO {
    private String codigoAeropuerto;
    private EstadoCapacidad estadoCapacidad;
    private double porcentajeOcupacion;
    private int maletasActuales;
    private int capacidadAlmacen;

    public EventoAeropuertoDTO(
            TipoEvento tipo, String fechaHoraEvento, String codigoAeropuerto,
            EstadoCapacidad estadoCapacidad, double porcentajeOcupacion, int maletasActuales,
            int capacidadAlmacen) {
        super(tipo, fechaHoraEvento);
        this.codigoAeropuerto = codigoAeropuerto;
        this.estadoCapacidad = estadoCapacidad;
        this.porcentajeOcupacion = porcentajeOcupacion;
        this.maletasActuales = maletasActuales;
        this.capacidadAlmacen = capacidadAlmacen;
    }


}
