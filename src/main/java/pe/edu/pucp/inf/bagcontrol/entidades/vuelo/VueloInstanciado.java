package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VueloInstanciado {

    private Vuelo vueloBase;
    private LocalDateTime fechaHoraSalida;
    private LocalDateTime fechaHoraLlegada;
    private int ocupacionActual = 0;

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
        return vueloBase != null && vueloBase.isEstaCancelado();
    }

    public boolean tieneCapacidadDisponible(int cantidadMaletas) {
        return (ocupacionActual + cantidadMaletas) <= getCapacidadMax();
    }

    public void asignarMaletas(int cantidadMaletas) {
        this.ocupacionActual += cantidadMaletas;
    }
}