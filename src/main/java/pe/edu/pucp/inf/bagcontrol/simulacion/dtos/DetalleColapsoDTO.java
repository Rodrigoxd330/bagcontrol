package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DetalleColapsoDTO {
    private String idPedido;
    private String origenIata;
    private String destinoIata;
    private int cantidadMaletas;
    private String motivo;
    private Long vueloAfectado;
    private String itinerarioAfectado;
    private String horaSimulada;
    private String tipo;
    private String codigoAeropuerto;
    private Integer capacidad;
    private Integer maletasActuales;
    private Double porcentajeOcupacion;

    public DetalleColapsoDTO(
            String idPedido,
            String origenIata,
            String destinoIata,
            int cantidadMaletas,
            String motivo,
            Long vueloAfectado,
            String itinerarioAfectado,
            String horaSimulada
    ) {
        this.idPedido = idPedido;
        this.origenIata = origenIata;
        this.destinoIata = destinoIata;
        this.cantidadMaletas = cantidadMaletas;
        this.motivo = motivo;
        this.vueloAfectado = vueloAfectado;
        this.itinerarioAfectado = itinerarioAfectado;
        this.horaSimulada = horaSimulada;
    }
}
