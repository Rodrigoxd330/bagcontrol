package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.NuevoEnvioDTO;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CargaMasivaController {

    private final AeropuertoRepository aeropuertoRepository;
    private final VueloRepository vueloRepository;
    private final EnvioDataStore envioDataStore;

    @PostMapping("/api/aeropuertos/cargar-csv")
    public ResponseEntity<Map<String, Object>> cargarAeropuertosCsv(
            @RequestParam("archivo") MultipartFile archivo) {

        int insertados = 0;
        List<String> errores = new ArrayList<>();
        int fila = 0;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(archivo.getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                fila++;
                linea = linea.trim();
                if (linea.isEmpty() || linea.startsWith("#") || linea.startsWith("codigoIata")) {
                    continue;
                }
                try {
                    String[] p = linea.split(",", -1);
                    if (p.length < 8) {
                        throw new IllegalArgumentException("Se esperan 8 columnas");
                    }
                    Aeropuerto a = new Aeropuerto();
                    a.setCodigoIata(p[0].trim().toUpperCase());
                    a.setCiudad(p[1].trim());
                    a.setPais(p[2].trim());
                    a.setContinente(p[3].trim());
                    a.setGmt(Integer.parseInt(p[4].trim()));
                    a.setCapacidadAlmacen(Integer.parseInt(p[5].trim()));
                    a.setLatitud(Double.parseDouble(p[6].trim()));
                    a.setLongitud(Double.parseDouble(p[7].trim()));
                    aeropuertoRepository.save(a);
                    insertados++;
                } catch (Exception e) {
                    errores.add("Fila " + fila + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No se pudo leer el archivo: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of("insertados", insertados, "errores", errores, "totalFilas", fila));
    }

    @PostMapping("/api/vuelos/cargar-csv")
    public ResponseEntity<Map<String, Object>> cargarVuelosCsv(
            @RequestParam("archivo") MultipartFile archivo) {

        int insertados = 0;
        List<String> errores = new ArrayList<>();
        int fila = 0;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(archivo.getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                fila++;
                linea = linea.trim();
                if (linea.isEmpty() || linea.startsWith("#")) {
                    continue;
                }
                try {
                    String[] p = linea.split("-");
                    if (p.length < 5) {
                        throw new IllegalArgumentException("Se esperan 5 campos separados por '-'");
                    }
                    Vuelo v = new Vuelo(
                            p[0].trim().toUpperCase(),
                            p[1].trim().toUpperCase(),
                            java.time.LocalTime.parse(p[2].trim()),
                            java.time.LocalTime.parse(p[3].trim()),
                            Integer.parseInt(p[4].trim())
                    );
                    v.setCreadoPorCrud(true);
                    vueloRepository.save(v);
                    insertados++;
                } catch (Exception e) {
                    errores.add("Fila " + fila + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No se pudo leer el archivo: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of("insertados", insertados, "errores", errores, "totalFilas", fila));
    }

    @PostMapping("/api/envios/cargar-csv")
    public ResponseEntity<Map<String, Object>> cargarEnviosCsv(
            @RequestParam("archivo") MultipartFile archivo,
            @RequestParam(value = "origenIata", required = false) String origenIataParam) {

        int insertados = 0;
        List<String> errores = new ArrayList<>();
        int fila = 0;

        Aeropuerto aeropuertoOrigenForzado = null;
        if (origenIataParam != null && !origenIataParam.isBlank()) {
            String iata = origenIataParam.trim().toUpperCase();
            aeropuertoOrigenForzado = aeropuertoRepository.findById(iata).orElse(null);
            if (aeropuertoOrigenForzado == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Aeropuerto origen '" + iata + "' no existe"));
            }
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(archivo.getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                fila++;
                linea = linea.trim();
                if (linea.isEmpty() || linea.startsWith("#")) {
                    continue;
                }
                try {
                    if (aeropuertoOrigenForzado != null) {
                        procesarFormatoNuevo(linea, aeropuertoOrigenForzado);
                    } else {
                        procesarFormatoLegado(linea);
                    }
                    insertados++;
                } catch (Exception e) {
                    errores.add("Fila " + fila + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No se pudo leer el archivo: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of("insertados", insertados, "errores", errores, "totalFilas", fila));
    }

    private void procesarFormatoNuevo(String linea, Aeropuerto aeropuertoOrigen) {
        String[] p = linea.split("-");
        if (p.length < 6) {
            throw new IllegalArgumentException("Formato esperado: idPedido-aaaammdd-hh-mm-dest-###-idCliente");
        }
        String idPedido = p[0].trim();
        String fechaStr = p[1].trim();
        String horaStr = p[2].trim();
        String minStr = p[3].trim();
        String destinoIata = p[4].trim().toUpperCase();
        String cantidadStr = p[5].trim();
        String idCliente;
        if (p.length >= 7) {
            idCliente = p[6].trim();
        } else {
            idCliente = "0000000";
        }

        if (!destinoIata.matches("^[A-Z]{3,4}$")) {
            throw new IllegalArgumentException("Destino '" + destinoIata + "' no es un codigo IATA valido");
        }
        if (!aeropuertoRepository.existsById(destinoIata)) {
            throw new IllegalArgumentException("Aeropuerto destino '" + destinoIata + "' no existe");
        }

        int cantidadMaletas = Integer.parseInt(cantidadStr);
        if (cantidadMaletas < 1) {
            throw new IllegalArgumentException("Cantidad de maletas debe ser al menos 1");
        }

        int year = Integer.parseInt(fechaStr.substring(0, 4));
        int month = Integer.parseInt(fechaStr.substring(4, 6));
        int day = Integer.parseInt(fechaStr.substring(6, 8));
        int hour = Integer.parseInt(horaStr);
        int minute = Integer.parseInt(minStr);

        LocalDateTime fechaHoraLocal = LocalDateTime.of(year, month, day, hour, minute);
        LocalDateTime fechaHoraUtc = LocalDateTime.ofInstant(
                fechaHoraLocal.toInstant(java.time.ZoneOffset.ofHours(aeropuertoOrigen.getGmt())),
                ZoneOffset.UTC
        );

        NuevoEnvioDTO dto = new NuevoEnvioDTO();
        dto.setOrigenIata(aeropuertoOrigen.getCodigoIata());
        dto.setDestinoIata(destinoIata);
        dto.setCantidadMaletas(cantidadMaletas);
        dto.setIdCliente(idCliente);
        dto.setFechaHora(fechaHoraUtc.toInstant(ZoneOffset.UTC).toString());

        Envio envio = envioDataStore.agregarEnvio(dto, aeropuertoOrigen);
        envio.setIdPedido(idPedido);
        envioDataStore.upsert(envio);
    }

    private void procesarFormatoLegado(String linea) {
        String[] p = linea.split(",", -1);
        if (p.length < 5) {
            throw new IllegalArgumentException("Se esperan 5 columnas (formato legado CSV)");
        }
        String origenIata = p[0].trim().toUpperCase();
        Aeropuerto aeropuerto = aeropuertoRepository.findById(origenIata)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Aeropuerto origen '" + origenIata + "' no existe"));
        NuevoEnvioDTO dto = new NuevoEnvioDTO(
                origenIata,
                p[1].trim().toUpperCase(),
                Integer.parseInt(p[2].trim()),
                p[3].trim(),
                p[4].trim()
        );
        envioDataStore.agregarEnvio(dto, aeropuerto);
    }
}
