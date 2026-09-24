package net.abled.medieval.core.catalogue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CataloguePageTest {

    private static final int SIZE = 36;

    @Test
    void anEmptyListingStillHasOnePage() {
        CataloguePage page = CataloguePage.of(0, SIZE, 0);

        assertEquals(1, page.pageCount());
        assertEquals(0, page.index());
        assertEquals(0, page.size());
        assertEquals(0, page.fromInclusive());
        assertEquals(0, page.toExclusive());
        assertTrue(page.isEmpty());
        assertFalse(page.hasPrevious());
        assertFalse(page.hasNext());
    }

    @Test
    void anExactMultipleFillsEveryPage() {
        CataloguePage second = CataloguePage.of(72, SIZE, 1);

        assertEquals(2, second.pageCount());
        assertEquals(36, second.fromInclusive());
        assertEquals(72, second.toExclusive());
        assertEquals(SIZE, second.size());
        assertTrue(second.hasPrevious());
        assertFalse(second.hasNext());
    }

    @Test
    void theLastPageHoldsTheRemainder() {
        CataloguePage last = CataloguePage.of(80, SIZE, 2);

        assertEquals(3, last.pageCount());
        assertEquals(72, last.fromInclusive());
        assertEquals(80, last.toExclusive());
        assertEquals(8, last.size());
        assertEquals(3, last.displayIndex());
        assertFalse(last.hasNext());
    }

    @Test
    void theRequestedPageIsClampedRatherThanRejected() {
        // A menu can be open while the listing behind it changes; a stale number must render the
        // nearest valid page instead of failing inside a click handler.
        assertEquals(0, CataloguePage.of(80, SIZE, -5).index());
        assertEquals(2, CataloguePage.of(80, SIZE, 99).index());
        assertEquals(0, CataloguePage.of(0, SIZE, 4).index());
    }

    @Test
    void shiftingStaysWithinTheListing() {
        CataloguePage first = CataloguePage.of(80, SIZE, 0);

        assertEquals(1, first.shift(80, SIZE, 1).index());
        assertEquals(0, first.shift(80, SIZE, -1).index());
        assertEquals(2, CataloguePage.of(80, SIZE, 2).shift(80, SIZE, 1).index());
    }

    @Test
    void rejectsImpossibleSizes() {
        assertThrows(IllegalArgumentException.class, () -> CataloguePage.of(-1, SIZE, 0));
        assertThrows(IllegalArgumentException.class, () -> CataloguePage.of(10, 0, 0));
    }

    @Test
    void rejectsAnIndexOutsideItsOwnRange() {
        assertThrows(IllegalArgumentException.class, () -> new CataloguePage(2, 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CataloguePage(-1, 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CataloguePage(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CataloguePage(0, 1, 5, 4));
    }
}
