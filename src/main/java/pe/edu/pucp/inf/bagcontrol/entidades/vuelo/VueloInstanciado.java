package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VueloInstanciado {

    private Vuelo vueloBase;
    private LocalDateTime fechaHoraSalida;
    private LocalDateTime fechaHoraLlegada;
    private Instant fechaHoraSalidaUtc;
    private Instant fechaHoraLlegadaUtc;
    private int ocupacionActual = 0;
    private boolean canceladoPorIncidencia = false;
    private String motivoCancelacion;

    public VueloInstanciado(
            Vuelo vueloBase,
            LocalDateTime fechaHoraSalida,
            LocalDateTime fechaHoraLlegada,
            Instant fechaHoraSalidaUtc,
            Instant fechaHoraLlegadaUtc,
            int ocupacionActual
    ) {
        this.vueloBase = vueloBase;
        this.fechaHoraSalida = fechaHoraSalida;
        this.fechaHoraLlegada = fechaHoraLlegada;
        this.fechaHoraSalidaUtc = fechaHoraSalidaUtc;
        this.fechaHoraLlegadaUtc = fechaHoraLlegadaUtc;
        this.ocupacionActual = ocupacionActual;
    }

    public VueloInstanciado(
            Vuelo vueloBase,
            LocalDateTime fechaHoraSalida,
            LocalDateTime fechaHoraLlegada,
            int ocupacionActual
    ) {
        this.vueloBase = vueloBase;
        this.fechaHoraSalida = fechaHoraSalida;
        this.fechaHoraLlegada = fechaHoraLlegada;
        this.ocupacionActual = ocupacionActual;
    }

    public Long getCodigoBase() {
        return vueloBase != null ? vueloBase.getCodigo() : null;
    }

    public String getOrigenIata() {
        return vueloBase != null ? vueloBase.getOrigenIata() : null;
    }

    public String getDestinoIata() {
        return vueloBase != null ? vueloBase.getDestinoIata() : null;
    }

    public int getCapacidadMax() {
        return vueloBase != null ? vueloBase.getCapacidadMax() : 0;
    }

    public boolean isEstaCancelado() {
        return canceladoPorIncidencia || (vueloBase != null && vueloBase.isEstaCancelado());
    }

    public boolean tieneCapacidadDisponible(int cantidadMaletas) {
        return (ocupacionActual + cantidadMaletas) <= getCapacidadMax();
    }

    public void asignarMaletas(int cantidadMaletas) {
        this.ocupacionActual += cantidadMaletas;
    }
}
