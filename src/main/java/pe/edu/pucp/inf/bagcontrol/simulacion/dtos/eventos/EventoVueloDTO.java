package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class EventoVueloDTO extends EventoBaseDTO {
    private Long codigoVuelo;
    private String origenIata;
    private String destinoIata;
    private EstadoCapacidad estado;
    private int cantidadMaletas;
    private int capacidadMax;
    private double porcentajeOcupacion;
    private String horaSalidaLocal;
    private String horaLlegadaLocal;
    private String horaSalidaUtc;
    private String horaLlegadaUtc;
    private String motivo;
    private List<String> codigoEnvios;

    public EventoVueloDTO(TipoEvento tipo, String fechaHoraEvento, Long codigoVuelo, String origenIata,
                          String destinoIata, EstadoCapacidad estado, int cantidadMaletas, String horaSalidaLocal,
                          String horaLlegadaLocal, String horaSalidaUtc, String horaLlegadaUtc,List<String> codigoEnvios) {
        super(tipo, fechaHoraEvento);
        this.codigoVuelo = codigoVuelo;
        this.origenIata = origenIata;
        this.destinoIata = destinoIata;
        this.estado = estado;
        this.cantidadMaletas = cantidadMaletas;
        this.horaSalidaLocal = horaSalidaLocal;
        this.horaLlegadaLocal = horaLlegadaLocal;
        this.horaSalidaUtc = horaSalidaUtc;
        this.horaLlegadaUtc = horaLlegadaUtc;
        this.codigoEnvios = codigoEnvios;
    }

}
