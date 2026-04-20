package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VueloRepository extends JpaRepository<Vuelo, String> {

}