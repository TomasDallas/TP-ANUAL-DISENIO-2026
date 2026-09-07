package ar.utn.donatrack.donaciones.models.entidad.necesidad;

import ar.utn.donatrack.donaciones.models.entidad.necesidad.periodicidades.Periodicidad;
import ar.utn.donatrack.donaciones.util.FechaHoraArgentina;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de la jerarquía de Necesidades de una entidad beneficiaria.
 *
 * El enunciado distingue dos tipos:
 *   - NecesidadExtraordinaria: surge por una situación puntual (inundación,
 *     mudanza). Se satisface cuando se junta la cantidad objetivo. No vence.
 *   - NecesidadRecurrente: forma parte del funcionamiento habitual (ej: 100
 *     paquetes de fideos por semana). Se satisface DENTRO de cada período y
 *     el contador se reinicia cuando el período vence.
 *
 * Además se prueba esCompatibleCon(), que es la base del algoritmo de asignación:
 * decide si una donación de cierta subcategoría sirve para esta necesidad.
 */
@DisplayName("Necesidad - necesidades extraordinarias y recurrentes")
class NecesidadTest {

    @Nested
    @DisplayName("recibirDonacion() - acumulación de lo recibido")
    class RecibirDonacion {

        @Test
        @DisplayName("Recibir una donación suma a la cantidad ya recibida")
        void sumaLaCantidad() {
            NecesidadExtraordinaria necesidad = new NecesidadExtraordinaria();
            necesidad.setCantidadObjetivo(100);

            necesidad.recibirDonacion(30);

            assertThat(necesidad.getCantidadRecibida()).isEqualTo(30);
        }

        @Test
        @DisplayName("Varias donaciones se acumulan")
        void acumulaVariasDonaciones() {
            NecesidadExtraordinaria necesidad = new NecesidadExtraordinaria();
            necesidad.setCantidadObjetivo(100);

            necesidad.recibirDonacion(30);
            necesidad.recibirDonacion(45);
            necesidad.recibirDonacion(10);

            assertThat(necesidad.getCantidadRecibida()).isEqualTo(85);
        }
    }

    @Nested
    @DisplayName("esCompatibleCon() - matching entre donación y necesidad")
    class EsCompatibleCon {

        /** Necesidad de ejemplo cuyo nombre y descripción mencionan alimentos. */
        private NecesidadExtraordinaria necesidadDeArroz() {
            NecesidadExtraordinaria necesidad = new NecesidadExtraordinaria();
            necesidad.setNombre("Arroz para el comedor");
            necesidad.setDescripcion("Necesitamos bolsas de fideos y arroz");
            necesidad.setCantidadObjetivo(100);
            return necesidad;
        }

        @Test
        @DisplayName("Es compatible si la subcategoría aparece en el NOMBRE de la necesidad")
        void matchPorNombre() {
            assertThat(necesidadDeArroz().esCompatibleCon("arroz")).isTrue();
        }

        @Test
        @DisplayName("Es compatible si la subcategoría aparece en la DESCRIPCIÓN")
        void matchPorDescripcion() {
            // "fideos" no está en el nombre pero sí en la descripción.
            assertThat(necesidadDeArroz().esCompatibleCon("fideos")).isTrue();
        }

        @Test
        @DisplayName("El matching ignora mayúsculas y minúsculas")
        void matchIgnoraCase() {
            assertThat(necesidadDeArroz().esCompatibleCon("ARROZ")).isTrue();
            assertThat(necesidadDeArroz().esCompatibleCon("Arroz")).isTrue();
        }

        @Test
        @DisplayName("NO es compatible con una subcategoría que no aparece en ningún texto")
        void sinMatch() {
            assertThat(necesidadDeArroz().esCompatibleCon("ropa")).isFalse();
        }

        @Test
        @DisplayName("Una subcategoría nula o en blanco nunca es compatible")
        void subcategoriaNulaOEnBlanco() {
            // Caso borde: si aceptáramos el blanco, contains("") daría true y toda
            // necesidad sería compatible con cualquier donación.
            assertThat(necesidadDeArroz().esCompatibleCon(null)).isFalse();
            assertThat(necesidadDeArroz().esCompatibleCon("   ")).isFalse();
        }

        @Test
        @DisplayName("Una necesidad sin nombre ni descripción no es compatible con nada")
        void necesidadSinTextos() {
            NecesidadExtraordinaria vacia = new NecesidadExtraordinaria();

            assertThat(vacia.esCompatibleCon("arroz")).isFalse();
        }
    }

    @Nested
    @DisplayName("NecesidadExtraordinaria - se satisface al alcanzar el objetivo")
    class Extraordinaria {

        private NecesidadExtraordinaria necesidadDe100() {
            NecesidadExtraordinaria necesidad = new NecesidadExtraordinaria();
            necesidad.setNombre("Colchones tras la inundación");
            necesidad.setCantidadObjetivo(100);
            return necesidad;
        }

        @Test
        @DisplayName("No está satisfecha mientras falte para el objetivo")
        void noSatisfechaSiFalta() {
            NecesidadExtraordinaria necesidad = necesidadDe100();
            necesidad.recibirDonacion(99);

            assertThat(necesidad.estaSatisfecha()).isFalse();
        }

        @Test
        @DisplayName("Queda satisfecha al alcanzar exactamente el objetivo")
        void satisfechaAlAlcanzarExacto() {
            NecesidadExtraordinaria necesidad = necesidadDe100();
            necesidad.recibirDonacion(100);

            assertThat(necesidad.estaSatisfecha()).isTrue();
        }

        @Test
        @DisplayName("Queda satisfecha si se supera el objetivo")
        void satisfechaAlSuperar() {
            NecesidadExtraordinaria necesidad = necesidadDe100();
            necesidad.recibirDonacion(150);

            assertThat(necesidad.estaSatisfecha()).isTrue();
        }

        @Test
        @DisplayName("Una vez satisfecha no vence: no depende de ninguna fecha")
        void noVence() {
            // A diferencia de la recurrente, la extraordinaria no se reinicia:
            // cubierta la emergencia, la necesidad queda cerrada.
            NecesidadExtraordinaria necesidad = necesidadDe100();
            necesidad.setFechaRegistro(LocalDate.now().minusYears(3));
            necesidad.recibirDonacion(100);

            assertThat(necesidad.estaSatisfecha()).isTrue();
        }
    }

    @Nested
    @DisplayName("NecesidadRecurrente - períodos y reinicio del contador")
    class Recurrente {

        /** Necesidad semanal de 100 unidades cuyo período arrancó en la fecha dada. */
        private NecesidadRecurrente semanalDesde(LocalDate inicioPeriodo) {
            NecesidadRecurrente necesidad = new NecesidadRecurrente();
            necesidad.setNombre("Fideos semanales");
            necesidad.setCantidadObjetivo(100);
            necesidad.setPeriodo(Periodicidad.SEMANAL);
            necesidad.setFechaInicioPeriodo(inicioPeriodo);
            return necesidad;
        }

        @Test
        @DisplayName("El período NO está vencido dentro de los días de la periodicidad")
        void periodoVigente() {
            // SEMANAL = 7 días. Arrancó hace 3 días, todavía está corriendo.
            NecesidadRecurrente necesidad = semanalDesde(LocalDate.of(2026, 3, 1));

            assertThat(necesidad.periodoVencido(LocalDate.of(2026, 3, 4))).isFalse();
        }

        @Test
        @DisplayName("El último día del período todavía cuenta como vigente")
        void ultimoDiaEsVigente() {
            // Caso borde exacto: inicio + 7 días es el límite; recién el día
            // siguiente se considera vencido.
            NecesidadRecurrente necesidad = semanalDesde(LocalDate.of(2026, 3, 1));

            assertThat(necesidad.periodoVencido(LocalDate.of(2026, 3, 8))).isFalse();
        }

        @Test
        @DisplayName("El período está vencido al día siguiente del límite")
        void periodoVencido() {
            NecesidadRecurrente necesidad = semanalDesde(LocalDate.of(2026, 3, 1));

            assertThat(necesidad.periodoVencido(LocalDate.of(2026, 3, 9))).isTrue();
        }

        @Test
        @DisplayName("Una necesidad sin fecha de inicio de período nunca se considera vencida")
        void sinFechaInicioNoVence() {
            NecesidadRecurrente necesidad = semanalDesde(null);

            assertThat(necesidad.periodoVencido(LocalDate.of(2030, 1, 1))).isFalse();
        }

        @Test
        @DisplayName("obtenerOGenerarPeriodoActual() reinicia el contador cuando el período venció")
        void periodoVencidoReiniciaElContador() {
            // Regla del enunciado: lo recibido la semana pasada no cuenta para la
            // semana actual; el objetivo se vuelve a exigir en cada período.
            NecesidadRecurrente necesidad = semanalDesde(LocalDate.of(2026, 3, 1));
            necesidad.recibirDonacion(80);

            LocalDate hoy = LocalDate.of(2026, 3, 15);
            necesidad.obtenerOGenerarPeriodoActual(hoy);

            assertThat(necesidad.getCantidadRecibida()).isZero();
            assertThat(necesidad.getFechaInicioPeriodo()).isEqualTo(hoy);
        }

        @Test
        @DisplayName("obtenerOGenerarPeriodoActual() NO toca nada si el período sigue vigente")
        void periodoVigenteNoReinicia() {
            NecesidadRecurrente necesidad = semanalDesde(LocalDate.of(2026, 3, 1));
            necesidad.recibirDonacion(80);

            necesidad.obtenerOGenerarPeriodoActual(LocalDate.of(2026, 3, 4));

            assertThat(necesidad.getCantidadRecibida()).isEqualTo(80);
            assertThat(necesidad.getFechaInicioPeriodo()).isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        @DisplayName("Está satisfecha si alcanzó el objetivo y el período sigue vigente")
        void satisfechaDentroDelPeriodo() {
            // estaSatisfecha() consulta la fecha actual, por eso el período se
            // ancla a hoy para que la prueba no dependa del día en que se corra.
            NecesidadRecurrente necesidad = semanalDesde(FechaHoraArgentina.hoy());
            necesidad.recibirDonacion(100);

            assertThat(necesidad.estaSatisfecha()).isTrue();
        }

        @Test
        @DisplayName("NO está satisfecha si alcanzó el objetivo pero el período ya venció")
        void objetivoAlcanzadoPeroPeriodoVencido() {
            // Diferencia central con la extraordinaria: aunque el contador diga 100,
            // como el período venció la necesidad vuelve a estar abierta.
            NecesidadRecurrente necesidad = semanalDesde(FechaHoraArgentina.hoy().minusMonths(2));
            necesidad.recibirDonacion(100);

            assertThat(necesidad.estaSatisfecha()).isFalse();
        }

        @Test
        @DisplayName("NO está satisfecha si el período está vigente pero falta cantidad")
        void faltaCantidadDentroDelPeriodo() {
            NecesidadRecurrente necesidad = semanalDesde(FechaHoraArgentina.hoy());
            necesidad.recibirDonacion(50);

            assertThat(necesidad.estaSatisfecha()).isFalse();
        }

        @Test
        @DisplayName("Cada periodicidad define su propia cantidad de días")
        void diasDeCadaPeriodicidad() {
            // Los valores salen del enunciado: semanal, mensual, cuatrimestral y anual.
            assertThat(Periodicidad.SEMANAL.getDias()).isEqualTo(7);
            assertThat(Periodicidad.MENSUAL.getDias()).isEqualTo(30);
            assertThat(Periodicidad.CUATRIMESTRAL.getDias()).isEqualTo(120);
            assertThat(Periodicidad.ANUAL.getDias()).isEqualTo(365);
        }
    }

    @Nested
    @DisplayName("Identidad de la necesidad")
    class Identidad {

        @Test
        @DisplayName("Toda necesidad recibe un id propio al crearse")
        void idAutogenerado() {
            // El id se usa para buscar y eliminar la necesidad dentro de la campaña.
            Necesidad una = new NecesidadExtraordinaria();
            Necesidad otra = new NecesidadExtraordinaria();

            assertThat(una.getId()).isNotNull();
            assertThat(otra.getId()).isNotNull();
            assertThat(una.getId()).isNotEqualTo(otra.getId());
        }
    }
}
