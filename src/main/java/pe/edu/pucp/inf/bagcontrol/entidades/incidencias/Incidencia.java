package pe.edu.pucp.inf.bagcontrol.entidades.incidencias;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "Incidencia")
public class Incidencia {

    @Id
    @GeneratedValue
    private Long id;

    private LocalDateTime fechaHora;
    private String descripcion;
    private String origenIata;
    private boolean noPuedeRecibir;
    private boolean noPuedeEnviar;
    private int tiempoRecuperacionMinutos;
}