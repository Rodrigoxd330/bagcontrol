package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.EnvioAeropuertoDTO;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EventoAeropuertoDTO extends EventoBaseDTO {
    private String codigoAeropuerto;
    private EstadoCapacidad estadoCapacidad;
    private double porcentajeOcupacion;
    private int maletasActuales;
    private int capacidadAlmacen;
    private List<EnvioAeropuertoDTO> enviosProximosAVencer; //opcional

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
        this.enviosProximosAVencer = new ArrayList<EnvioAeropuertoDTO>();
    }

    public EventoAeropuertoDTO(
            TipoEvento tipo, String fechaHoraEvento, String codigoAeropuerto,
            EstadoCapacidad estadoCapacidad, double porcentajeOcupacion, int maletasActuales,
            int capacidadAlmacen, List<EnvioAeropuertoDTO> enviosProximosAVencer) {
        super(tipo, fechaHoraEvento);
        this.codigoAeropuerto = codigoAeropuerto;
        this.estadoCapacidad = estadoCapacidad;
        this.porcentajeOcupacion = porcentajeOcupacion;
        this.maletasActuales = maletasActuales;
        this.capacidadAlmacen = capacidadAlmacen;
        this.enviosProximosAVencer = enviosProximosAVencer;
    }


}
