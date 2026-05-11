package pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.model.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.repo.AeropuertoRepository;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AeropuertoService {
    private final AeropuertoRepository aeropuertoRepository;

    public List<Aeropuerto> findAll() {
        return aeropuertoRepository.findAll();
    }
}
