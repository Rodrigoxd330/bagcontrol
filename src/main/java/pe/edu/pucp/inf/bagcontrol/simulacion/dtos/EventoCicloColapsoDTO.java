package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventoCicloColapsoDTO extends EventoBaseDTO {
    private MetricasColapsoDTO metricas;

    public EventoCicloColapsoDTO(String fechaHoraEvento, MetricasColapsoDTO metricas) {
        super(TipoEvento.CICLO_COLAPSO_EVALUADO, fechaHoraEvento);
        this.metricas = metricas;
    }
}
