package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventoCanceladoDTO extends EventoBaseDTO {
    private String idPedido;
    private String origenIata;
    private String destinoIata;
    private int cantidadMaletas;
    private String motivo;

    public EventoCanceladoDTO(
            String fechaHoraEvento,
            String idPedido,
            String origenIata,
            String destinoIata,
            int cantidadMaletas,
            String motivo
    ) {
        super(TipoEvento.VUELO_CANCELADO, fechaHoraEvento);
        this.idPedido = idPedido;
        this.origenIata = origenIata;
        this.destinoIata = destinoIata;
        this.cantidadMaletas = cantidadMaletas;
        this.motivo = motivo;
    }
}
