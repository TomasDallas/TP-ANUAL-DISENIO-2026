package ar.utn.donatrack.logistica.services;

import ar.utn.donatrack.logistica.dtos.request.CallbackParadaDTO;
import ar.utn.donatrack.logistica.dtos.request.CallbackRutaRequestDTO;
import ar.utn.donatrack.logistica.dtos.request.CallbackVehiculoRutaDTO;
import ar.utn.donatrack.logistica.dtos.request.DireccionRequestDTO;
import ar.utn.donatrack.logistica.dtos.request.DonacionParaRutearRequestDTO;
import ar.utn.donatrack.logistica.dtos.request.PlanificacionRequestDTO;
import ar.utn.donatrack.logistica.dtos.response.LoteResponseDTO;
import ar.utn.donatrack.logistica.dtos.response.RutaPlanificadaProveedorDTO;
import ar.utn.donatrack.logistica.dtos.response.RutaResponseDTO;
import ar.utn.donatrack.logistica.eventos.EntregaEvento;
import ar.utn.donatrack.logistica.eventos.TipoEventoLogistica;
import ar.utn.donatrack.logistica.exceptions.CamionNoEncontradoException;
import ar.utn.donatrack.logistica.exceptions.LoteCallbackInvalidoException;
import ar.utn.donatrack.logistica.exceptions.RutaNoEncontradaException;
import ar.utn.donatrack.logistica.integracion.EntregaEventPublisher;
import ar.utn.donatrack.logistica.interfaces.integracion.EstrategiaRuteoPort;
import ar.utn.donatrack.logistica.interfaces.repositories.CamionRepositoryInterface;
import ar.utn.donatrack.logistica.interfaces.repositories.EntregaRepositoryInterface;
import ar.utn.donatrack.logistica.interfaces.repositories.LotePlanificacionRepositoryInterface;
import ar.utn.donatrack.logistica.interfaces.repositories.RutaRepositoryInterface;
import ar.utn.donatrack.logistica.models.entrega.Entrega;
import ar.utn.donatrack.logistica.models.entrega.EstadoEntrega;
import ar.utn.donatrack.logistica.models.flota.Camion;
import ar.utn.donatrack.logistica.models.flota.EstadoCamion;
import ar.utn.donatrack.logistica.models.planificacion.DonacionLote;
import ar.utn.donatrack.logistica.models.planificacion.EstadoLote;
import ar.utn.donatrack.logistica.models.planificacion.EstadoRuta;
import ar.utn.donatrack.logistica.models.planificacion.LotePlanificacion;
import ar.utn.donatrack.logistica.models.planificacion.Parada;
import ar.utn.donatrack.logistica.models.planificacion.Ruta;
import ar.utn.donatrack.logistica.validations.EntregaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanificacionRutasServiceTest {

    @Mock
    private LotePlanificacionRepositoryInterface loteRepositorio;
    @Mock
    private RutaRepositoryInterface rutaRepositorio;
    @Mock
    private EntregaRepositoryInterface entregaRepositorio;
    @Mock
    private CamionRepositoryInterface camionRepositorio;
    @Mock
    private EstrategiaRuteoPort estrategiaRuteo;
    @Mock
    private EntregaEventPublisher eventPublisher;

    private PlanificacionRutasService service;

    @BeforeEach
    void setUp() {
        service = nuevoServicio(100);
    }

    private PlanificacionRutasService nuevoServicio(int maxDonacionesPorLote) {
        return new PlanificacionRutasService(
                loteRepositorio, rutaRepositorio, entregaRepositorio, camionRepositorio,
                estrategiaRuteo, eventPublisher, new EntregaValidator(), maxDonacionesPorLote);
    }

    private DireccionRequestDTO direccionDTO() {
        DireccionRequestDTO direccion = new DireccionRequestDTO();
        direccion.setCalle("Av. Siempre Viva");
        direccion.setNumero(742);
        direccion.setLocalidad("Springfield");
        direccion.setProvincia("Buenos Aires");
        direccion.setCodigoPostal("1900");
        return direccion;
    }

    private DonacionParaRutearRequestDTO donacionDTO(UUID idEntidad) {
        DonacionParaRutearRequestDTO donacion = new DonacionParaRutearRequestDTO();
        donacion.setIdDonacion(UUID.randomUUID());
        donacion.setIdEntidadBeneficiaria(idEntidad);
        donacion.setDireccionEntrega(direccionDTO());
        return donacion;
    }

    /** Respuesta síncrona típica del proveedor/mock: una sola parada agrupando todo lo recibido. */
    private RutaPlanificadaProveedorDTO rutaPlanificadaPara(UUID camionId, UUID idEntidad, List<UUID> idsDonaciones) {
        CallbackParadaDTO parada = new CallbackParadaDTO();
        parada.setOrden(1);
        parada.setIdEntidadBeneficiaria(idEntidad);
        parada.setDireccion(direccionDTO());
        parada.setDonacionesIds(idsDonaciones);

        RutaPlanificadaProveedorDTO ruta = new RutaPlanificadaProveedorDTO();
        ruta.setCamionId(camionId);
        ruta.setParadas(List.of(parada));
        return ruta;
    }

    @Nested
    @DisplayName("planificar()")
    class Planificar {

        @Test
        @DisplayName("Particiona las donaciones en lotes según el máximo configurado y planifica cada lote contra el proveedor")
        void particionaEnLotesSegunElMaximo() {
            PlanificacionRutasService servicioConLotesChicos = nuevoServicio(2);

            UUID idEntidad = UUID.randomUUID();
            UUID idCamion = UUID.randomUUID();
            Camion camion = Camion.builder().id(idCamion).patente("AB123CD").build();
            when(camionRepositorio.buscarPorIds(List.of(idCamion))).thenReturn(List.of(camion));
            when(estrategiaRuteo.planificarParaCamion(any(), eq(camion), anyList()))
                    .thenAnswer(inv -> {
                        List<DonacionLote> donaciones = inv.getArgument(2);
                        List<UUID> ids = donaciones.stream().map(DonacionLote::getIdDonacion).toList();
                        return rutaPlanificadaPara(idCamion, idEntidad, ids);
                    });

            PlanificacionRequestDTO dto = new PlanificacionRequestDTO();
            dto.setCamionesIds(List.of(idCamion));
            dto.setDonaciones(List.of(donacionDTO(idEntidad), donacionDTO(idEntidad), donacionDTO(idEntidad)));

            List<LoteResponseDTO> lotes = servicioConLotesChicos.planificar(dto);

            assertEquals(2, lotes.size());
            assertEquals(EstadoLote.COMPLETADO, lotes.get(0).getEstado());
            verify(estrategiaRuteo, times(2)).planificarParaCamion(any(), eq(camion), anyList());
            verify(loteRepositorio, times(4)).guardar(any()); // 1 al crear + 1 al completar, por cada uno de los 2 lotes

            ArgumentCaptor<LotePlanificacion> captor = ArgumentCaptor.forClass(LotePlanificacion.class);
            verify(loteRepositorio, times(4)).guardar(captor.capture());
            List<Integer> tamaniosDeLote = captor.getAllValues().stream()
                    .map(l -> l.getDonaciones().size())
                    .distinct()
                    .sorted()
                    .toList();
            assertEquals(List.of(1, 2), tamaniosDeLote);
        }

        @Test
        @DisplayName("Reparte las donaciones round-robin entre los camiones y arma una ruta por camión con destinos")
        void reparteDonacionesEntreCamionesYDevuelveDestinos() {
            UUID idEntidad1 = UUID.randomUUID();
            UUID idEntidad2 = UUID.randomUUID();
            UUID idCamion1 = UUID.randomUUID();
            UUID idCamion2 = UUID.randomUUID();
            Camion camion1 = Camion.builder().id(idCamion1).patente("AB123CD").build();
            Camion camion2 = Camion.builder().id(idCamion2).patente("XY987ZW").build();
            when(camionRepositorio.buscarPorIds(List.of(idCamion1, idCamion2))).thenReturn(List.of(camion1, camion2));

            when(estrategiaRuteo.planificarParaCamion(any(), eq(camion1), anyList()))
                    .thenAnswer(inv -> {
                        List<DonacionLote> donaciones = inv.getArgument(2);
                        return rutaPlanificadaPara(idCamion1, idEntidad1, donaciones.stream().map(DonacionLote::getIdDonacion).toList());
                    });
            when(estrategiaRuteo.planificarParaCamion(any(), eq(camion2), anyList()))
                    .thenAnswer(inv -> {
                        List<DonacionLote> donaciones = inv.getArgument(2);
                        return rutaPlanificadaPara(idCamion2, idEntidad2, donaciones.stream().map(DonacionLote::getIdDonacion).toList());
                    });

            PlanificacionRequestDTO dto = new PlanificacionRequestDTO();
            dto.setCamionesIds(List.of(idCamion1, idCamion2));
            dto.setDonaciones(List.of(donacionDTO(idEntidad1), donacionDTO(idEntidad2)));

            List<LoteResponseDTO> lotes = service.planificar(dto);

            assertEquals(1, lotes.size());
            LoteResponseDTO lote = lotes.getFirst();
            assertEquals(EstadoLote.COMPLETADO, lote.getEstado());
            assertNotNull(lote.getFechaRespuesta());
            assertEquals(2, lote.getRutas().size());

            List<UUID> camionesConRuta = lote.getRutas().stream().map(RutaResponseDTO::getCamionId).toList();
            assertEquals(List.of(idCamion1, idCamion2), camionesConRuta);

            RutaResponseDTO rutaCamion1 = lote.getRutas().get(0);
            assertEquals(1, rutaCamion1.getParadas().size());
            assertEquals(idEntidad1, rutaCamion1.getParadas().getFirst().getIdEntidadBeneficiaria());
            assertEquals(1, rutaCamion1.getParadas().getFirst().getEntregasIds().size());

            ArgumentCaptor<Entrega> entregaCaptor = ArgumentCaptor.forClass(Entrega.class);
            verify(entregaRepositorio, times(2)).guardar(entregaCaptor.capture());
            assertEquals(List.of(EstadoEntrega.LISTO_PARA_ENTREGAR, EstadoEntrega.LISTO_PARA_ENTREGAR),
                    entregaCaptor.getAllValues().stream().map(Entrega::getEstado).toList());
        }

        @Test
        @DisplayName("Camión inexistente lanza CamionNoEncontradoException y no llama al proveedor")
        void camionInexistenteLanzaExcepcion() {
            UUID idCamion = UUID.randomUUID();
            when(camionRepositorio.buscarPorIds(List.of(idCamion))).thenReturn(List.of());

            PlanificacionRequestDTO dto = new PlanificacionRequestDTO();
            dto.setCamionesIds(List.of(idCamion));
            dto.setDonaciones(List.of(donacionDTO(UUID.randomUUID())));

            assertThrows(CamionNoEncontradoException.class, () -> service.planificar(dto));
            verify(estrategiaRuteo, never()).planificarParaCamion(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("registrarCallback()")
    class RegistrarCallback {

        @Test
        @DisplayName("Token de correlación inválido (header Authorization) lanza LoteCallbackInvalidoException")
        void tokenInvalidoLanzaExcepcion() {
            UUID loteId = UUID.randomUUID();
            LotePlanificacion lote = LotePlanificacion.builder()
                    .id(loteId)
                    .tokenCorrelacion("token-correcto")
                    .donaciones(List.of())
                    .estado(EstadoLote.ENVIADO)
                    .build();
            when(loteRepositorio.buscarPorId(loteId)).thenReturn(lote);

            CallbackRutaRequestDTO dto = new CallbackRutaRequestDTO();
            dto.setLoteId(loteId);
            dto.setRutas(List.of());

            assertThrows(LoteCallbackInvalidoException.class, () -> service.registrarCallback(dto, "token-equivocado"));
        }

        @Test
        @DisplayName("Crea Ruta, Parada y Entrega a partir del callback; la entidad queda en la Parada")
        void creaRutaYEntregasDesdeElCallback() {
            UUID loteId = UUID.randomUUID();
            UUID idDonacion = UUID.randomUUID();
            UUID idEntidad = UUID.randomUUID();
            UUID idCamion = UUID.randomUUID();

            LotePlanificacion lote = LotePlanificacion.builder()
                    .id(loteId)
                    .tokenCorrelacion("token-123")
                    .estado(EstadoLote.ENVIADO)
                    .donaciones(List.of(DonacionLote.builder()
                            .idDonacion(idDonacion)
                            .idEntidadBeneficiaria(idEntidad)
                            .build()))
                    .build();
            when(loteRepositorio.buscarPorId(loteId)).thenReturn(lote);

            Camion camion = Camion.builder().id(idCamion).build();
            when(camionRepositorio.buscarPorId(idCamion)).thenReturn(camion);

            CallbackParadaDTO parada = new CallbackParadaDTO();
            parada.setOrden(1);
            parada.setIdEntidadBeneficiaria(idEntidad);
            parada.setDireccion(direccionDTO());
            parada.setDonacionesIds(List.of(idDonacion));

            CallbackVehiculoRutaDTO vehiculo = new CallbackVehiculoRutaDTO();
            vehiculo.setCamionId(idCamion);
            vehiculo.setParadas(List.of(parada));

            CallbackRutaRequestDTO dto = new CallbackRutaRequestDTO();
            dto.setLoteId(loteId);
            dto.setRutas(List.of(vehiculo));

            service.registrarCallback(dto, "token-123");

            ArgumentCaptor<Ruta> rutaCaptor = ArgumentCaptor.forClass(Ruta.class);
            verify(rutaRepositorio).guardar(rutaCaptor.capture());
            Ruta rutaGuardada = rutaCaptor.getValue();
            assertEquals(idCamion, rutaGuardada.getCamion().getId());
            assertEquals(EstadoRuta.PLANIFICADA, rutaGuardada.getEstado());
            assertEquals(1, rutaGuardada.getParadas().size());
            assertEquals(idEntidad, rutaGuardada.getParadas().getFirst().getIdEntidadBeneficiaria());

            ArgumentCaptor<Entrega> entregaCaptor = ArgumentCaptor.forClass(Entrega.class);
            verify(entregaRepositorio).guardar(entregaCaptor.capture());
            Entrega entregaGuardada = entregaCaptor.getValue();
            assertEquals(idDonacion, entregaGuardada.getIdDonacion());
            assertEquals(EstadoEntrega.LISTO_PARA_ENTREGAR, entregaGuardada.getEstado());
            assertEquals(rutaGuardada.getParadas().getFirst(), entregaGuardada.getParada());
            assertEquals(1, rutaGuardada.obtenerEntregas().size());
            assertEquals(entregaGuardada.getId(), rutaGuardada.obtenerEntregas().getFirst().getId());

            ArgumentCaptor<LotePlanificacion> loteCaptor = ArgumentCaptor.forClass(LotePlanificacion.class);
            verify(loteRepositorio).guardar(loteCaptor.capture());
            assertEquals(EstadoLote.COMPLETADO, loteCaptor.getValue().getEstado());
            assertNotNull(loteCaptor.getValue().getFechaRespuesta());
        }
    }

    @Nested
    @DisplayName("iniciarRuta()")
    class IniciarRuta {

        @Test
        @DisplayName("Pone la ruta INICIADA, el camión EN_RUTA, las entregas EN_TRASLADO y publica INICIO_RUTA")
        void iniciaRutaYPropagaCambios() {
            UUID rutaId = UUID.randomUUID();
            UUID camionId = UUID.randomUUID();

            Camion camion = Camion.builder().id(camionId).estado(EstadoCamion.DISPONIBLE).build();

            Parada parada = Parada.builder()
                    .id(UUID.randomUUID())
                    .orden(1)
                    .entregas(new ArrayList<>())
                    .build();
            Entrega entrega = Entrega.builder()
                    .id(UUID.randomUUID())
                    .idDonacion(UUID.randomUUID())
                    .parada(parada)
                    .estado(EstadoEntrega.LISTO_PARA_ENTREGAR)
                    .build();
            parada.getEntregas().add(entrega);
            Ruta ruta = Ruta.builder()
                    .id(rutaId)
                    .camion(camion)
                    .estado(EstadoRuta.PLANIFICADA)
                    .paradas(List.of(parada))
                    .build();
            when(rutaRepositorio.buscarPorId(rutaId)).thenReturn(ruta);

            service.iniciarRuta(rutaId);

            assertEquals(EstadoRuta.INICIADA, ruta.getEstado());
            assertNotNull(ruta.getFechaInicio());

            assertEquals(EstadoCamion.EN_RUTA, camion.getEstado());
            verify(camionRepositorio).guardar(camion);

            assertEquals(EstadoEntrega.EN_TRASLADO, entrega.getEstado());
            verify(entregaRepositorio).guardar(entrega);

            ArgumentCaptor<EntregaEvento> eventoCaptor = ArgumentCaptor.forClass(EntregaEvento.class);
            verify(eventPublisher).publicar(eventoCaptor.capture());
            assertEquals(TipoEventoLogistica.INICIO_RUTA, eventoCaptor.getValue().getTipo());
            assertEquals(rutaId, eventoCaptor.getValue().getRutaId());
        }
    }

    @Nested
    @DisplayName("finalizarRutaSiCorresponde()")
    class FinalizarRutaSiCorresponde {

        @Test
        @DisplayName("Con entregas aún EN_TRASLADO, no finaliza la ruta ni libera el camión")
        void noFinalizaSiQuedanEntregasEnTraslado() {
            UUID rutaId = UUID.randomUUID();
            Camion camion = Camion.builder().id(UUID.randomUUID()).estado(EstadoCamion.EN_RUTA).build();
            Parada parada = Parada.builder()
                    .id(UUID.randomUUID())
                    .orden(1)
                    .entregas(List.of(
                            Entrega.builder().id(UUID.randomUUID()).estado(EstadoEntrega.ENTREGADA).build(),
                            Entrega.builder().id(UUID.randomUUID()).estado(EstadoEntrega.EN_TRASLADO).build()))
                    .build();
            Ruta ruta = Ruta.builder().id(rutaId).camion(camion).estado(EstadoRuta.INICIADA).paradas(List.of(parada)).build();
            when(rutaRepositorio.buscarPorId(rutaId)).thenReturn(ruta);

            service.finalizarRutaSiCorresponde(rutaId);

            assertEquals(EstadoRuta.INICIADA, ruta.getEstado());
            assertEquals(EstadoCamion.EN_RUTA, camion.getEstado());
            verify(rutaRepositorio, never()).guardar(any());
            verify(camionRepositorio, never()).guardar(any());
        }

        @Test
        @DisplayName("Con todas las entregas en estado terminal, finaliza la ruta y libera el camión")
        void finalizaRutaYLiberaCamionCuandoNoQuedanEntregasEnTraslado() {
            UUID rutaId = UUID.randomUUID();
            Camion camion = Camion.builder().id(UUID.randomUUID()).estado(EstadoCamion.EN_RUTA).build();
            Parada parada = Parada.builder()
                    .id(UUID.randomUUID())
                    .orden(1)
                    .entregas(List.of(
                            Entrega.builder().id(UUID.randomUUID()).estado(EstadoEntrega.ENTREGADA).build(),
                            Entrega.builder().id(UUID.randomUUID()).estado(EstadoEntrega.NO_RECIBIDA).build()))
                    .build();
            Ruta ruta = Ruta.builder().id(rutaId).camion(camion).estado(EstadoRuta.INICIADA).paradas(List.of(parada)).build();
            when(rutaRepositorio.buscarPorId(rutaId)).thenReturn(ruta);

            service.finalizarRutaSiCorresponde(rutaId);

            assertEquals(EstadoRuta.FINALIZADA, ruta.getEstado());
            verify(rutaRepositorio).guardar(ruta);

            assertEquals(EstadoCamion.DISPONIBLE, camion.getEstado());
            verify(camionRepositorio).guardar(camion);
        }

        @Test
        @DisplayName("Una ruta que no está INICIADA se ignora")
        void ignoraRutasQueNoEstanIniciadas() {
            UUID rutaId = UUID.randomUUID();
            Ruta ruta = Ruta.builder().id(rutaId).estado(EstadoRuta.PLANIFICADA).paradas(List.of()).build();
            when(rutaRepositorio.buscarPorId(rutaId)).thenReturn(ruta);

            service.finalizarRutaSiCorresponde(rutaId);

            assertEquals(EstadoRuta.PLANIFICADA, ruta.getEstado());
            verify(rutaRepositorio, never()).guardar(any());
        }
    }

    @Nested
    @DisplayName("obtenerRutaVigentePorCamion()")
    class ObtenerRutaVigente {

        @Test
        @DisplayName("Sin rutas activas para el camión, lanza RutaNoEncontradaException")
        void sinRutasActivasLanzaExcepcion() {
            UUID camionId = UUID.randomUUID();
            when(rutaRepositorio.buscarPorCamionId(camionId)).thenReturn(List.of());

            assertThrows(RutaNoEncontradaException.class, () -> service.obtenerRutaVigentePorCamion(camionId));
        }

        @Test
        @DisplayName("Ignora rutas FINALIZADA y devuelve la vigente")
        void devuelveLaRutaVigenteIgnorandoFinalizadas() {
            UUID camionId = UUID.randomUUID();
            Camion camion = Camion.builder().id(camionId).build();
            Ruta finalizada = Ruta.builder().id(UUID.randomUUID()).camion(camion)
                    .estado(EstadoRuta.FINALIZADA).paradas(List.of()).build();
            Ruta vigente = Ruta.builder().id(UUID.randomUUID()).camion(camion)
                    .estado(EstadoRuta.INICIADA).paradas(List.of()).build();
            when(rutaRepositorio.buscarPorCamionId(camionId)).thenReturn(List.of(finalizada, vigente));

            RutaResponseDTO resultado = service.obtenerRutaVigentePorCamion(camionId);

            assertEquals(vigente.getId(), resultado.getId());
        }
    }
}
