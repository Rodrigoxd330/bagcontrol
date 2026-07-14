package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.VueloInstanciadoDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.ConsultaOperativaService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;



@RestController
@RequiredArgsConstructor
public class ConsultaOperativaController {

    private final ConsultaOperativaService consultaOperativaService;

    @GetMapping("/api/envios/ventana")
    public List<EnvioDTO> obtenerEnviosEnVentana(
            @RequestParam("inicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime inicio,
            @RequestParam("fin")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime fin
    ) {
        return consultaOperativaService.obtenerEnviosEnVentana(inicio, fin);
    }

    @GetMapping("/api/vuelos/instanciados")
    public List<VueloInstanciadoDTO> obtenerVuelosInstanciados(
            @RequestParam("fecha")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fecha
    ) {
        return consultaOperativaService.obtenerVuelosInstanciados(fecha);
    }
    @GetMapping("/api/envios/rango-dias")
    public List<EnvioDTO> obtenerEnviosPorDias(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaInicio,
            @RequestParam("dias")
            int dias
    ) {
        return consultaOperativaService.obtenerEnviosPorDias(fechaInicio, dias);
    }

    @GetMapping("/api/envios/paginados")
    public Page<EnvioDTO> obtenerEnviosPorDiasPaginados(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaInicio,
            @RequestParam("dias")
            int dias,
            @RequestParam(required = false) String origenIata,
            @RequestParam(required = false) String destinoIata,
            @RequestParam(required = false) String idCliente,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer maletasMin,
            @RequestParam(required = false) Integer maletasMax,
            @RequestParam(required = false) Boolean esOperacionDia,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return consultaOperativaService.obtenerEnviosPorDiasPaginados(
                fechaInicio, dias,
                origenIata, destinoIata, idCliente, q,
                maletasMin, maletasMax,
                pageable, esOperacionDia
        );
    }
}
