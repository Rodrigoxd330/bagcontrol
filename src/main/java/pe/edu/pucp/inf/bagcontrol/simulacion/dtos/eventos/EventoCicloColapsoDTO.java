package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.MetricasColapsoDTO;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventoCicloColapsoDTO extends EventoBaseDTO {
    private int ciclo;
    private MetricasColapsoDTO metricas;

    public EventoCicloColapsoDTO(String fechaHoraEvento, int ciclo, MetricasColapsoDTO metricas) {
        super(TipoEvento.CICLO_COLAPSO_EVALUADO, fechaHoraEvento);
        this.ciclo = ciclo;
        this.metricas = metricas;
    }
}
