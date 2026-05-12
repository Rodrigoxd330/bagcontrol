package pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.model.Ciudad;

@Repository
public interface CiudadRepository extends JpaRepository<Ciudad, Long> {
}