package pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
    @JsonIgnore
    public String claveInstanciaVuelo() {
        return this.codigoVuelo + "|" + this.horaSalidaUtc;
    }

    public VueloInstanciado toVueloInstanciado() {
        Vuelo vueloBase = new Vuelo();
        vueloBase.setCodigo(this.codigoVuelo);
        vueloBase.setOrigenIata(this.origenIata);
        vueloBase.setDestinoIata(this.destinoIata);
        vueloBase.setCapacidadMax(this.capacidadMax);
        vueloBase.setEstaCancelado(this.getTipo() == TipoEvento.VUELO_CANCELADO);

        VueloInstanciado vuelo = new VueloInstanciado(
                vueloBase,
                parseFechaHoraLocal(this.horaSalidaLocal),
                parseFechaHoraLocal(this.horaLlegadaLocal),
                parseFechaHoraUtc(this.horaSalidaUtc),
                parseFechaHoraUtc(this.horaLlegadaUtc),
                this.cantidadMaletas
        );
        vuelo.setCanceladoPorIncidencia(this.getTipo() == TipoEvento.VUELO_CANCELADO);
        vuelo.setMotivoCancelacion(this.motivo);
        return vuelo;
    }

    private static LocalDateTime parseFechaHoraLocal(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }

        String limpio = valor.trim();
        try {
            if (contieneZona(limpio)) {
                return LocalDateTime.ofInstant(Instant.parse(limpio), ZoneOffset.UTC);
            }
            return LocalDateTime.parse(limpio);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(limpio).toLocalDateTime();
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("No se pudo parsear la fecha local: " + valor, ex);
            }
        }
    }

    private static Instant parseFechaHoraUtc(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }

        String limpio = valor.trim();
        try {
            return Instant.parse(limpio);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(limpio).toInstant();
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("No se pudo parsear la fecha UTC: " + valor, ex);
            }
        }
    }

    private static boolean contieneZona(String valor) {
        return valor.contains("Z") || valor.contains("z") || valor.contains("+") || valor.lastIndexOf("-") > 10;
    }

}
