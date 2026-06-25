package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EnvioRepository extends JpaRepository<Envio, String> {
    List<Envio> findByFechaHoraBetween(LocalDateTime fechaHoraAfter, LocalDateTime fechaHoraBefore);
}
