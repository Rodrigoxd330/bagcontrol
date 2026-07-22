package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pe.edu.pucp.inf.bagcontrol.auth.AuthService;
import pe.edu.pucp.inf.bagcontrol.auth.UsuarioSesion;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.NuevoEnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.motor.SimulacionManager;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.VueloTxtImportService;

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
    private final AuthService authService;
    private final SimulacionManager simulacionManager;
    private final VueloTxtImportService vueloTxtImportService;

    private enum FormatoEnvioImportacion {
        ORIGEN_USUARIO,
        LEGACY_CSV
    }

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

    @PostMapping({"/api/vuelos/cargar-txt", "/api/vuelos/cargar-csv"})
    public ResponseEntity<Map<String, Object>> cargarVuelosTxt(
            @RequestParam("archivo") MultipartFile archivo) {
        var resultado = vueloTxtImportService.importar(archivo);
        return ResponseEntity.ok(Map.of(
                "insertados", resultado.insertados(),
                "errores", resultado.errores(),
                "totalFilas", resultado.totalLineas(),
                "duplicadosDetectados", resultado.duplicadosDetectados()
        ));
    }

    @PostMapping("/api/envios/cargar-csv")
    public ResponseEntity<Map<String, Object>> cargarEnviosCsv(
            @RequestParam("archivo") MultipartFile archivo,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        int insertados = 0;
        List<String> errores = new ArrayList<>();
        int fila = 0;

        UsuarioSesion usuario = authService.resolverBearer(authorization);
        if (usuario == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Se requiere una sesión autenticada.");
        }
        String aeropuertoUsuario = normalizarIata(usuario.aeropuerto());
        if (aeropuertoUsuario == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "La cuenta actual no tiene un aeropuerto de origen asignado."
            ));
        }
        Aeropuerto aeropuertoOrigen = aeropuertoRepository.findById(aeropuertoUsuario).orElse(null);
        if (aeropuertoOrigen == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El aeropuerto de origen asignado a la cuenta no existe: " + aeropuertoUsuario + "."
            ));
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(archivo.getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                fila++;
                linea = linea.trim().replace("\uFEFF", "");
                if (linea.isEmpty() || linea.startsWith("#")) {
                    continue;
                }
                try {
                    FormatoEnvioImportacion formato = detectarFormatoEnvio(linea);
                    int cantidadCampos = contarCampos(linea, formato);
                    System.out.println("[ENVIO-IMPORT-FORMAT] linea=" + linea
                            + " cantidadCampos=" + cantidadCampos
                            + " formatoDetectado=" + formato
                            + " origenIncluidoEnLinea=" + (formato == FormatoEnvioImportacion.LEGACY_CSV)
                            + " usuarioAutenticado=" + usuario.email()
                            + " aeropuertoOrigenUsuario=" + aeropuertoUsuario);
                    if (formato == FormatoEnvioImportacion.ORIGEN_USUARIO) {
                        procesarFormatoOrigenUsuario(linea, aeropuertoOrigen);
                    } else {
                        procesarFormatoLegado(linea, aeropuertoOrigen);
                    }
                    insertados++;
                } catch (Exception e) {
                    errores.add("Formato inválido en la línea " + fila + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No se pudo leer el archivo: " + e.getMessage()));
        }

        if (insertados > 0) {
            simulacionManager.solicitarPlanificacionOperacionDia();
        }
        return ResponseEntity.ok(Map.of("insertados", insertados, "errores", errores, "totalFilas", fila));
    }

    private FormatoEnvioImportacion detectarFormatoEnvio(String linea) {
        if (linea.contains(",")) {
            return FormatoEnvioImportacion.LEGACY_CSV;
        }
        if (linea.contains("-")) {
            return FormatoEnvioImportacion.ORIGEN_USUARIO;
        }
        throw new IllegalArgumentException("no coincide con un formato soportado");
    }

    private int contarCampos(String linea, FormatoEnvioImportacion formato) {
        return formato == FormatoEnvioImportacion.LEGACY_CSV
                ? linea.split(",", -1).length
                : linea.split("-", -1).length;
    }

    private void procesarFormatoOrigenUsuario(String linea, Aeropuerto aeropuertoOrigen) {
        String[] p = linea.split("-", -1);
        if (p.length != 7) {
            throw new IllegalArgumentException("Formato esperado: idPedido-aaaammdd-hh-mm-dest-###-idCliente");
        }
        String idPedido = p[0].trim();
        String fechaStr = p[1].trim();
        String horaStr = p[2].trim();
        String minStr = p[3].trim();
        String destinoIata = p[4].trim().toUpperCase();
        String cantidadStr = p[5].trim();
        String idCliente = p[6].trim();

        if (!idPedido.matches("^[A-Za-z0-9]+$")) {
            throw new IllegalArgumentException("identificador de envío inválido");
        }
        if (!fechaStr.matches("^\\d{8}$") || !horaStr.matches("^\\d{2}$")
                || !minStr.matches("^\\d{2}$") || !cantidadStr.matches("^\\d{1,3}$")
                || !"0007729".equals(idCliente)) {
            throw new IllegalArgumentException("campos obligatorios incompletos o mal formados");
        }

        if (!destinoIata.matches("^[A-Z]{3,4}$")) {
            throw new IllegalArgumentException("Destino '" + destinoIata + "' no es un codigo IATA valido");
        }
        if (!aeropuertoRepository.existsById(destinoIata)) {
            throw new IllegalArgumentException("No existe el aeropuerto destino " + destinoIata + ".");
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

        Envio envio = new Envio();
        envio.setIdPedido(idPedido);
        envio.setOrigenIata(aeropuertoOrigen.getCodigoIata());
        envio.setDestinoIata(destinoIata);
        envio.setCantidadMaletas(cantidadMaletas);
        envio.setIdCliente(idCliente);
        envio.setFechaHora(fechaHoraUtc);
        envio.setActivo(true);
        envio.setEsOperacionDia(true);
        envioDataStore.upsert(envio);
        System.out.println("[ENVIO-IMPORT-PARSE] id=" + idPedido
                + " fecha=" + fechaStr + " hora=" + horaStr + " minuto=" + minStr
                + " origen=" + aeropuertoOrigen.getCodigoIata() + " destino=" + destinoIata
                + " cantidadMaletas=" + cantidadMaletas + " codigoFinal=" + idCliente
                + " valido=true motivo=");
    }

    private void procesarFormatoLegado(String linea, Aeropuerto aeropuertoOrigen) {
        String[] p = linea.split(",", -1);
        if (p.length != 5) {
            throw new IllegalArgumentException("Se esperan 5 columnas (formato legado CSV)");
        }
        String origenIata = p[0].trim().toUpperCase();
        if (!aeropuertoOrigen.getCodigoIata().equals(origenIata)) {
            throw new IllegalArgumentException("el origen del formato legado no coincide con el aeropuerto de la cuenta");
        }
        NuevoEnvioDTO dto = new NuevoEnvioDTO(
                origenIata,
                p[1].trim().toUpperCase(),
                Integer.parseInt(p[2].trim()),
                "0007729",
                p[4].trim(),
                true
        );
        envioDataStore.agregarEnvio(dto, aeropuertoOrigen);
    }

    private String normalizarIata(String codigoIata) {
        if (codigoIata == null || codigoIata.isBlank()) {
            return null;
        }
        return codigoIata.trim().toUpperCase();
    }
}
