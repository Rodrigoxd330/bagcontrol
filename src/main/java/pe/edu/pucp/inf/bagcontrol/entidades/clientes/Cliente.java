package pe.edu.pucp.inf.bagcontrol.entidades.clientes;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "Cliente")
public class Cliente {

    @Id
    private String ruc;

    private String nombre;
    private String email;
    private String telefono;
    private String sedePrincipal;
}