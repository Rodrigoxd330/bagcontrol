package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventoSimulacionDTO {

    private String simulacionId;

    // Número secuencial del evento dentro de la simulación
    private Long numeroEvento;

    // Fecha/hora real en la que el backend emite el evento
    private String fechaHoraEvento;

    // Tipos posibles:
    // SIMULACION_INICIADA
    // AEROPUERTO_ACTUALIZADO
    // VUELO_DESPEGA
    // VUELO_ATERRIZA
    // SIMULACION_FINALIZADA
    // ERROR
    private String tipo;

    private String mensaje;
    private String estado;

    // Datos para eventos de aeropuerto
    private String codigoAeropuerto;
    private Integer maletasActuales;
    private Integer capacidadAlmacen;
    private Double porcentajeOcupacion;

    // Datos para eventos de vuelo
    private Long codigoVuelo;
    private String origenIata;
    private String destinoIata;
    private String horaSalida;
    private String horaLlegada;
    private Integer cantidadMaletas;

    private Map<String, Object> extra;
}