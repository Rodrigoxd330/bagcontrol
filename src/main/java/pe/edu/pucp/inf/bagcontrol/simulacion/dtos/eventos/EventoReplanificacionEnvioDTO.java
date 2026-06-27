package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventoReplanificacionEnvioDTO extends EventoBaseDTO {
    private String idPedido;
    private String motivo;
    private String origenIata;
    private String destinoIata;
    private String itinerarioAnterior;
    private String itinerarioNuevo;
    private String vueloAnterior;
    private String vueloNuevo;
    private String estadoAnterior;
    private String estadoNuevo;
    private String horaSimulada;
    private String detalle;

    public EventoReplanificacionEnvioDTO(
            String fechaHoraEvento,
            String idPedido,
            String motivo,
            String origenIata,
            String destinoIata,
            String itinerarioAnterior,
            String itinerarioNuevo,
            String vueloAnterior,
            String vueloNuevo,
            String estadoAnterior,
            String estadoNuevo,
            String horaSimulada,
            String detalle
    ) {
        super(TipoEvento.REPLANIFICACION_ENVIO, fechaHoraEvento);
        this.idPedido = idPedido;
        this.motivo = motivo;
        this.origenIata = origenIata;
        this.destinoIata = destinoIata;
        this.itinerarioAnterior = itinerarioAnterior;
        this.itinerarioNuevo = itinerarioNuevo;
        this.vueloAnterior = vueloAnterior;
        this.vueloNuevo = vueloNuevo;
        this.estadoAnterior = estadoAnterior;
        this.estadoNuevo = estadoNuevo;
        this.horaSimulada = horaSimulada;
        this.detalle = detalle;
    }
}
