package pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "Ciudad")
public class Ciudad {

    @Id
    @GeneratedValue
    private Long id;

    private String nombre;
    private String pais;
    private String continente;
}