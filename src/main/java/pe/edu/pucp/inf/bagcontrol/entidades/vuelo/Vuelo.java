package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalTime;

@Data
@Entity
@Table(name = "Vuelo")
public class Vuelo {
    @Id()
    @GeneratedValue
    private Long codigo;

    private String origenIata;
    private String destinoIata;
    private LocalTime horaSalida;
    private LocalTime horaLlegada;
    private int capacidadMax;
    private boolean estaCancelado = false;

    public Vuelo(String origenIata, String destinoIata, LocalTime horaSalida, LocalTime horaLlegada, int capacidadMax) {
        this.origenIata = origenIata;
        this.destinoIata = destinoIata;
        this.horaSalida = horaSalida;
        this.horaLlegada = horaLlegada;
        this.capacidadMax = capacidadMax;
    }

    public Vuelo() {

    }
}
