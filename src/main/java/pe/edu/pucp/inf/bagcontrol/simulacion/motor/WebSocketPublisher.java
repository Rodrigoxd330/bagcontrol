package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.eventos.LoteEventosDTO;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class WebSocketPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final Set<String> simulacionesConPrimerLote = ConcurrentHashMap.newKeySet();

    public void publicarLote(String simulacionId, LoteEventosDTO lote) {
        if (simulacionesConPrimerLote.add(simulacionId)) {
            String primerTipo = lote.getEventos() != null && !lote.getEventos().isEmpty()
                    ? String.valueOf(lote.getEventos().get(0).getTipo()) : "SIN_EVENTOS";
            System.out.println("[BACK-SIM-TIME] primer evento enviado por WebSocket id=" + simulacionId
                    + " lote=" + lote.getNumeroLote()
                    + " tipo=" + primerTipo
                    + " eventos=" + lote.getCantidadEventos()
                    + " ts=" + java.time.Instant.now());
            System.out.println("[BACK-SIM-TIME] primer lote enviado id=" + simulacionId
                    + " lote=" + lote.getNumeroLote()
                    + " ts=" + java.time.Instant.now());
        }
        messagingTemplate.convertAndSend("/topic/simulacion/" + simulacionId + "/eventos", lote);
    }

}
