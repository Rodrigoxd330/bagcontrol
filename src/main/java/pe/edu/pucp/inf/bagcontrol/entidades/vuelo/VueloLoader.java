package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order(2) // Se ejecuta primero
@RequiredArgsConstructor
public class VueloLoader implements CommandLineRunner{

    private final VueloRepository vueloRepository;

    @Value("${tasf.b2b.data.vuelos}")
    private Resource vuelosRes;

    @Override
    public void run(String... args) throws Exception{
        System.out.println("==================================================");
        System.out.println("✈️ 2. Iniciando carga de Vuelos desde archivo...");
        System.out.println("==================================================");

        List<Vuelo> vuelosNuevos = new ArrayList<>();
        Set<String> clavesBaseExistentes = vueloRepository.findAll().stream()
                .filter(vuelo -> !vuelo.isCreadoPorCrud())
                .map(this::claveNatural)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        try (BufferedReader br = new BufferedReader(new InputStreamReader(vuelosRes.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine())!=null) {
                String[] params = line.split("-");
                String origenIata = params[0].trim().toUpperCase();
                String destinoIata = params[1].trim().toUpperCase();
                LocalTime horaSalida = LocalTime.parse(params[2].trim());
                LocalTime horaLlegada = LocalTime.parse(params[3].trim());
                int capacidadMax = Integer.parseInt(params[4].trim());
                String clave = claveNatural(origenIata, destinoIata, horaSalida, horaLlegada, capacidadMax);
                if (clavesBaseExistentes.add(clave)) {
                    Vuelo vuelo = new Vuelo(origenIata, destinoIata, horaSalida, horaLlegada, capacidadMax);
                    vuelo.setCreadoPorCrud(false);
                    vuelosNuevos.add(vuelo);
                }
            }

            vueloRepository.saveAll(vuelosNuevos);
            long vuelosCrudPreservados = vueloRepository.findAll().stream()
                    .filter(Vuelo::isCreadoPorCrud)
                    .count();
            System.out.println("Proceso terminado con exito: vuelosBaseInsertados=" + vuelosNuevos.size()
                    + " vuelosCrudPreservados=" + vuelosCrudPreservados);
        }
        catch(Exception err){
            System.out.println("Error durante la carga de vuelos");
            System.out.println(err.getMessage());
            err.printStackTrace();
        }
    }

    private String claveNatural(Vuelo vuelo) {
        return claveNatural(
                vuelo.getOrigenIata(),
                vuelo.getDestinoIata(),
                vuelo.getHoraSalida(),
                vuelo.getHoraLlegada(),
                vuelo.getCapacidadMax()
        );
    }

    private String claveNatural(
            String origenIata,
            String destinoIata,
            LocalTime horaSalida,
            LocalTime horaLlegada,
            int capacidadMax
    ) {
        return origenIata + "|" + destinoIata + "|" + horaSalida + "|" + horaLlegada + "|" + capacidadMax;
    }
}
